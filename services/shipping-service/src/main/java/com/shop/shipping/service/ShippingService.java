package com.shop.shipping.service;

import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.event.payload.ShippingEvents.ShipmentChanged;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.shipping.domain.Carrier;
import com.shop.shipping.domain.Shipment;
import com.shop.shipping.domain.ShipmentEvent;
import com.shop.shipping.domain.ShipmentEventRepository;
import com.shop.shipping.domain.ShipmentRepository;
import com.shop.shipping.domain.ShipmentStatus;
import com.shop.shipping.web.ShippingDtos.CarrierDto;
import com.shop.shipping.web.ShippingDtos.ShipmentDto;
import com.shop.shipping.web.ShippingDtos.SummaryDto;
import com.shop.support.kafka.EventReader;
import com.shop.support.audit.AuditTrail;
import com.shop.support.outbox.IdempotencyGuard;
import com.shop.support.outbox.OutboxService;
import com.shop.support.web.Access;
import com.shop.support.web.ApiException;
import com.shop.support.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shipping Service: tạo vận đơn khi đơn hàng được xác nhận (OrderConfirmed), nhân viên kho/giao hàng cập nhật
 * từng bước, mỗi bước phát sự kiện lên shipping-events (key = orderId) để Order Service cập nhật trạng thái đơn.
 * <p>
 * Đồng bộ ngược từ order-events (OrderStatusChanged SHIPPING/DELIVERED, OrderCancelled) giúp read-model đúng cả khi
 * đọc lại lịch sử topic; các cập nhật đồng bộ này KHÔNG phát sự kiện để tránh vòng lặp.
 */
@Service
public class ShippingService {

    private static final Logger log = LoggerFactory.getLogger(ShippingService.class);
    static final String CONSUMER = "shipping-service";
    private static final String[] OPERATORS = {Roles.ADMIN, Roles.WAREHOUSE_MANAGER};
    private static final String[] VIEWERS = {Roles.ADMIN, Roles.WAREHOUSE_MANAGER, Roles.ANALYST};

    private final ShipmentRepository shipments;
    private final ShipmentEventRepository timeline;
    private final OutboxService outbox;
    private final IdempotencyGuard guard;
    private final EventReader reader;
    private final AuditTrail audit;

    public ShippingService(ShipmentRepository shipments, ShipmentEventRepository timeline, OutboxService outbox,
                           IdempotencyGuard guard, EventReader reader, AuditTrail audit) {
        this.audit = audit;
        this.shipments = shipments;
        this.timeline = timeline;
        this.outbox = outbox;
        this.guard = guard;
        this.reader = reader;
    }

    // ------------------------------------------------------------------ Kafka (order-events)

    @Transactional
    public void handleOrderEvent(EventEnvelope env) {
        switch (env.eventType()) {
            case EventTypes.ORDER_CONFIRMED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    create(reader.payload(env, OrderStatusChanged.class));
                }
            }
            case EventTypes.ORDER_CANCELLED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    OrderStatusChanged p = reader.payload(env, OrderStatusChanged.class);
                    shipments.findByOrderId(p.orderId())
                            .filter(s -> !s.getStatus().isFinal())
                            .ifPresent(s -> change(s, ShipmentStatus.CANCELLED, "Đơn hàng bị hủy: " + p.reason(), true));
                }
            }
            case EventTypes.ORDER_STATUS_CHANGED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    syncFromOrder(reader.payload(env, OrderStatusChanged.class));
                }
            }
            default -> {
            }
        }
    }

    private void create(OrderStatusChanged p) {
        if (shipments.findByOrderId(p.orderId()).isPresent()) {
            return;
        }
        Shipment s = shipments.save(new Shipment(p.orderId(), p.userId(), p.shippingAddress(), p.totalAmount()));
        timeline.save(new ShipmentEvent(s.getId(), ShipmentStatus.CREATED,
                "Tạo vận đơn " + s.getTrackingNumber() + " (" + s.getCarrier().displayName() + "), chờ đóng gói"));
        publish(EventTypes.SHIPMENT_CREATED, s, null, null);
        log.info("Tạo vận đơn {} cho đơn #{}", s.getTrackingNumber(), p.orderId());
    }

    /** Đơn được chuyển trạng thái giao hàng bằng cách khác (dữ liệu cũ) → cập nhật vận đơn, không phát sự kiện. */
    private void syncFromOrder(OrderStatusChanged p) {
        shipments.findByOrderId(p.orderId()).ifPresent(s -> {
            if ("SHIPPING".equals(p.status()) && !s.getStatus().leftWarehouse() && !s.getStatus().isFinal()) {
                if (s.getStatus() == ShipmentStatus.CREATED) {
                    change(s, ShipmentStatus.PACKED, "Đồng bộ từ đơn hàng", false);
                }
                change(s, ShipmentStatus.HANDED_OVER, "Đồng bộ từ đơn hàng", false);
            } else if ("DELIVERED".equals(p.status()) && s.getStatus() != ShipmentStatus.DELIVERED
                    && s.getStatus().leftWarehouse()) {
                change(s, ShipmentStatus.DELIVERED, "Đồng bộ từ đơn hàng", false);
            }
        });
    }

    // ------------------------------------------------------------------ REST

    @Transactional
    public ShipmentDto updateStatus(AuthenticatedUser user, long shipmentId, String status, String note) {
        Access.requireAnyRole(user, OPERATORS);
        Shipment s = load(shipmentId);
        ShipmentStatus next;
        try {
            next = ShipmentStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Trạng thái vận đơn không hợp lệ: " + status);
        }
        if (!s.getStatus().staffNext().contains(next)) {
            throw ApiException.conflict("Không thể chuyển vận đơn từ " + s.getStatus() + " sang " + next);
        }
        String text = note == null || note.isBlank() ? defaultNote(next, s) : note.trim();
        ShipmentStatus previous = s.getStatus();
        change(s, next, text + " – " + user.email(), true);
        audit.record(user, "SHIPMENT_STATUS_UPDATED", "SHIPMENT", s.getId(),
                "Đơn #" + s.getOrderId() + ": " + previous + " → " + next);
        return toDto(s, true);
    }

    @Transactional
    public ShipmentDto changeCarrier(AuthenticatedUser user, long shipmentId, String carrierCode) {
        Access.requireAnyRole(user, OPERATORS);
        Shipment s = load(shipmentId);
        if (s.getStatus().leftWarehouse() || s.getStatus().isFinal()) {
            throw ApiException.conflict("Vận đơn đã bàn giao, không thể đổi đơn vị vận chuyển");
        }
        Carrier carrier;
        try {
            carrier = Carrier.valueOf(carrierCode);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Đơn vị vận chuyển không hợp lệ: " + carrierCode);
        }
        if (carrier != s.getCarrier()) {
            audit.record(user, "SHIPMENT_CARRIER_CHANGED", "SHIPMENT", s.getId(),
                    "Đơn #" + s.getOrderId() + ": " + s.getCarrier() + " → " + carrier);
            s.assignCarrier(carrier);
            timeline.save(new ShipmentEvent(s.getId(), s.getStatus(),
                    "Đổi sang " + carrier.displayName() + ", mã vận đơn " + s.getTrackingNumber()));
            publish(EventTypes.SHIPMENT_STATUS_CHANGED, s, s.getStatus(), "Đổi đơn vị vận chuyển");
        }
        return toDto(s, true);
    }

    @Transactional(readOnly = true)
    public ShipmentDto byOrder(AuthenticatedUser user, long orderId) {
        Shipment s = shipments.findByOrderId(orderId)
                .orElseThrow(() -> ApiException.notFound("Đơn hàng chưa có vận đơn"));
        if (!user.hasAnyRole(VIEWERS) && !s.getUserId().equals(user.userId())) {
            throw ApiException.notFound("Đơn hàng chưa có vận đơn");
        }
        return toDto(s, true);
    }

    @Transactional(readOnly = true)
    public ShipmentDto get(AuthenticatedUser user, long shipmentId) {
        Access.requireAnyRole(user, VIEWERS);
        return toDto(load(shipmentId), true);
    }

    @Transactional(readOnly = true)
    public PageResponse<ShipmentDto> list(AuthenticatedUser user, String status, int page, int size) {
        Access.requireAnyRole(user, VIEWERS);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        if (status == null || status.isBlank()) {
            return PageResponse.of(shipments.findAllByOrderByCreatedAtDesc(pageable), s -> toDto(s, false));
        }
        try {
            // Theo hàng đợi: vận đơn chờ lâu nhất lên đầu
            return PageResponse.of(shipments.findByStatusOrderByUpdatedAtAsc(ShipmentStatus.valueOf(status), pageable),
                    s -> toDto(s, false));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Trạng thái vận đơn không hợp lệ: " + status);
        }
    }

    @Transactional(readOnly = true)
    public SummaryDto summary(AuthenticatedUser user) {
        Access.requireAnyRole(user, VIEWERS);
        return new SummaryDto(toMap(shipments.countByStatus()), toMap(shipments.countByCarrier()), carriers());
    }

    public List<CarrierDto> carriers() {
        return Arrays.stream(Carrier.values())
                .map(c -> new CarrierDto(c.name(), c.displayName(), c.baseFee())).toList();
    }

    // ------------------------------------------------------------------ Nội bộ

    private void change(Shipment s, ShipmentStatus next, String note, boolean publish) {
        ShipmentStatus previous = s.transitionTo(next);
        timeline.save(new ShipmentEvent(s.getId(), next, note));
        if (publish) {
            publish(next == ShipmentStatus.DELIVERED ? EventTypes.SHIPMENT_DELIVERED : EventTypes.SHIPMENT_STATUS_CHANGED,
                    s, previous, note);
        }
    }

    private void publish(String type, Shipment s, ShipmentStatus previous, String note) {
        outbox.publish(Topics.SHIPPING_EVENTS, type, String.valueOf(s.getOrderId()),
                new ShipmentChanged(s.getId(), s.getOrderId(), s.getUserId(), s.getCarrier().name(),
                        s.getTrackingNumber(), previous == null ? null : previous.name(), s.getStatus().name(),
                        s.getFee(), note));
    }

    private static String defaultNote(ShipmentStatus next, Shipment s) {
        return switch (next) {
            case PACKED -> "Đã đóng gói, chờ bàn giao";
            case HANDED_OVER -> "Đã bàn giao cho " + s.getCarrier().displayName();
            case IN_TRANSIT -> "Đang trên đường giao tới khách";
            case DELIVERED -> "Giao hàng thành công";
            default -> next.name();
        };
    }

    private Shipment load(long id) {
        return shipments.findById(id).orElseThrow(() -> ApiException.notFound("Không tìm thấy vận đơn"));
    }

    private ShipmentDto toDto(Shipment s, boolean withTimeline) {
        return ShipmentDto.from(s, withTimeline ? timeline.findByShipmentIdOrderByIdAsc(s.getId()) : null);
    }

    private static Map<String, Long> toMap(List<Object[]> rows) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Object[] row : rows) {
            map.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return map;
    }
}
