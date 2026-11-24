package com.shop.order.service;

import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.NotificationEvents.NotificationRequested;
import com.shop.common.event.payload.OrderEvents.OrderCreated;
import com.shop.common.event.payload.OrderEvents.OrderLine;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.event.payload.OrderEvents.PaymentRequested;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.order.OrderProperties;
import com.shop.order.domain.Order;
import com.shop.order.domain.OrderRepository;
import com.shop.order.domain.OrderStatus;
import com.shop.order.domain.OrderStatusHistory;
import com.shop.order.domain.OrderStatusHistoryRepository;
import com.shop.order.domain.ProductSnapshot;
import com.shop.order.domain.ProductSnapshotRepository;
import com.shop.order.web.OrderDtos.CreateOrderRequest;
import com.shop.order.web.OrderDtos.ItemRequest;
import com.shop.order.web.OrderDtos.OrderDto;
import com.shop.order.web.OrderDtos.UpdateStatusRequest;
import com.shop.support.audit.AuditTrail;
import com.shop.support.outbox.OutboxService;
import com.shop.support.web.Access;
import com.shop.support.web.ApiException;
import com.shop.support.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** Trạng thái vận đơn mà từ đó đơn hàng được coi là "đang giao". */
    private static final Set<String> SHIPMENT_IN_TRANSIT = Set.of("HANDED_OVER", "IN_TRANSIT");

    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final ProductSnapshotRepository products;
    private final OutboxService outbox;
    private final OrderProperties props;
    private final AuditTrail audit;

    public OrderService(OrderRepository orders, OrderStatusHistoryRepository history,
                        ProductSnapshotRepository products, OutboxService outbox, OrderProperties props,
                        AuditTrail audit) {
        this.audit = audit;
        this.orders = orders;
        this.history = history;
        this.products = products;
        this.outbox = outbox;
        this.props = props;
    }

    // ------------------------------------------------------------------ Lệnh từ REST

    /**
     * Bước 1-3 của luồng đặt hàng: kiểm tra dữ liệu, lưu đơn PENDING và ghi OrderCreated vào outbox
     * trong cùng một transaction.
     */
    @Transactional
    public OrderDto createOrder(AuthenticatedUser user, CreateOrderRequest req) {
        Map<Long, Integer> merged = new LinkedHashMap<>();
        for (ItemRequest item : req.items()) {
            merged.merge(item.productId(), item.quantity(), Integer::sum);
        }
        Order order = new Order(user.userId(), req.paymentMethod(), req.shippingAddress().trim());
        for (Map.Entry<Long, Integer> e : merged.entrySet()) {
            if (e.getValue() > props.maxQuantityPerItem()) {
                throw ApiException.badRequest("Mỗi sản phẩm chỉ được đặt tối đa " + props.maxQuantityPerItem());
            }
            ProductSnapshot p = products.findById(e.getKey())
                    .orElseThrow(() -> ApiException.badRequest("Sản phẩm #" + e.getKey() + " không tồn tại"));
            if (!p.isActive()) {
                throw ApiException.badRequest("Sản phẩm '" + p.getName() + "' đã ngừng kinh doanh");
            }
            order.addItem(p.getProductId(), p.getName(), p.getPrice(), e.getValue());
        }
        order.applyShippingFee(props.shippingFeeFor(order.getTotalAmount()));
        if (!props.fraudCheckEnabled()) {
            order.clearRisk();
        }
        String voucher = req.voucherCode() == null || req.voucherCode().isBlank() ? null
                : req.voucherCode().trim().toUpperCase(Locale.ROOT);
        if (voucher != null && !props.promotionCheckEnabled()) {
            throw ApiException.badRequest("Chương trình khuyến mãi đang tạm dừng, vui lòng đặt hàng không kèm mã giảm giá");
        }
        order.requirePromotion(voucher, props.promotionCheckEnabled());
        orders.save(order);
        history.save(new OrderStatusHistory(order.getId(), null, OrderStatus.PENDING, "Khách hàng tạo đơn"
                + (voucher == null ? "" : " (mã giảm giá " + voucher + ")")));

        outbox.publish(Topics.ORDER_EVENTS, EventTypes.ORDER_CREATED, key(order),
                new OrderCreated(order.getId(), order.getUserId(), lines(order), order.getTotalAmount(),
                        order.getPaymentMethod(), order.getCreatedAt(), order.getShippingAddress(),
                        order.getShippingFee(), voucher));
        notifyCustomer(order, "ORDER_CREATED", "Đã nhận đơn hàng #" + order.getId(),
                "Đơn hàng tạm tính " + money(order.getTotalAmount()) + " đang được xử lý"
                        + (props.promotionCheckEnabled() ? " (ưu đãi sẽ được trừ khi xác nhận)." : "."));
        log.info("Tạo đơn #{} cho user {} tổng {}", order.getId(), user.userId(), order.getTotalAmount());
        return OrderDto.from(order);
    }

    @Transactional
    public OrderDto cancelByCustomer(AuthenticatedUser user, long orderId) {
        Order order = load(orderId);
        if (!order.getUserId().equals(user.userId())) {
            throw ApiException.notFound("Không tìm thấy đơn hàng");
        }
        if (order.getStatus() == OrderStatus.SHIPPING || order.getStatus() == OrderStatus.DELIVERED
                || order.getStatus() == OrderStatus.CANCELLED) {
            throw ApiException.conflict("Đơn hàng ở trạng thái " + order.getStatus() + " không thể hủy");
        }
        transition(order, OrderStatus.CANCELLED, "Khách hàng hủy đơn");
        return OrderDto.from(order);
    }

    /**
     * Nhân viên chỉ còn được HỦY đơn từ đây. Trạng thái giao hàng (SHIPPING/DELIVERED) do Shipping Service
     * quyết định qua shipping-events; xác minh rủi ro do Fraud Service (fraud-alert-events).
     */
    @Transactional
    public OrderDto updateStatusByStaff(AuthenticatedUser user, long orderId, UpdateStatusRequest req) {
        Access.requireAnyRole(user, Roles.ADMIN, Roles.WAREHOUSE_MANAGER);
        OrderStatus next;
        try {
            next = OrderStatus.valueOf(req.status());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Trạng thái không hợp lệ: " + req.status());
        }
        if (next == OrderStatus.SHIPPING || next == OrderStatus.DELIVERED) {
            throw ApiException.badRequest("Trạng thái giao hàng được cập nhật từ Quản lý vận chuyển (Shipping Service)");
        }
        if (next != OrderStatus.CANCELLED) {
            throw ApiException.badRequest("Trạng thái " + next + " do Saga tự động xử lý");
        }
        Order order = load(orderId);
        if (!order.getStatus().canTransitionTo(next)) {
            throw ApiException.conflict("Không thể chuyển từ " + order.getStatus() + " sang " + next);
        }
        String note = req.note() == null || req.note().isBlank() ? "Hủy bởi " + user.email() : req.note();
        OrderStatus previous = order.getStatus();
        transition(order, next, note);
        audit.record(user, "ORDER_CANCELLED_BY_STAFF", "ORDER", order.getId(),
                previous + " → CANCELLED: " + note + " (" + money(order.getTotalAmount()) + ")");
        return OrderDto.from(order, null, true);
    }

    // ------------------------------------------------------------------ Truy vấn

    @Transactional(readOnly = true)
    public OrderDto getOrder(AuthenticatedUser user, long orderId) {
        Order order = load(orderId);
        boolean staff = isStaff(user);
        if (!staff && !order.getUserId().equals(user.userId())) {
            throw ApiException.notFound("Không tìm thấy đơn hàng");
        }
        return OrderDto.from(order, history.findByOrderIdOrderByIdAsc(orderId), staff);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderDto> listMine(AuthenticatedUser user, int page, int size) {
        return PageResponse.of(orders.findByUserIdOrderByCreatedAtDesc(user.userId(), pageable(page, size)),
                OrderDto::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderDto> listAll(AuthenticatedUser user, String status, int page, int size) {
        Access.requireAnyRole(user, Roles.ADMIN, Roles.WAREHOUSE_MANAGER, Roles.ANALYST);
        if (status == null || status.isBlank()) {
            return PageResponse.of(orders.findAllByOrderByCreatedAtDesc(pageable(page, size)),
                    o -> OrderDto.from(o, null, true));
        }
        try {
            return PageResponse.of(orders.findByStatusOrderByCreatedAtDesc(OrderStatus.valueOf(status),
                    pageable(page, size)), o -> OrderDto.from(o, null, true));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Trạng thái không hợp lệ: " + status);
        }
    }

    // ------------------------------------------------------------------ Các bước Saga (gọi trong transaction của handler)

    /** Kho đã giữ hàng → yêu cầu thanh toán nếu rủi ro cũng đã được chấp nhận. */
    public void onInventoryReserved(long orderId) {
        findOrder(orderId).ifPresent(order -> {
            if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.UNDER_REVIEW) {
                log.info("Bỏ qua InventoryReserved cho đơn #{} đang ở {}", orderId, order.getStatus());
                return;
            }
            order.markInventoryReserved();
            if (order.getStatus() == OrderStatus.PENDING && order.readyForPayment()) {
                transition(order, OrderStatus.AWAITING_PAYMENT, "Đã giữ hàng, chờ thanh toán");
            }
        });
    }

    public void onInventoryRejected(long orderId, String reason) {
        findOrder(orderId).ifPresent(order -> {
            if (order.getStatus() == OrderStatus.PENDING || order.getStatus() == OrderStatus.UNDER_REVIEW) {
                transition(order, OrderStatus.CANCELLED, "Không đủ tồn kho: " + reason);
            }
        });
    }

    /**
     * Kết quả chấm điểm rủi ro từ Fraud Service. Rủi ro thấp/trung bình → chấp nhận tự động;
     * rủi ro cao → UNDER_REVIEW, hàng vẫn được giữ trong lúc chờ nhân viên xác minh.
     */
    public void onRiskAssessed(long orderId, int score, String level, boolean requiresReview, String summary) {
        findOrder(orderId).ifPresent(order -> {
            order.recordRisk(score, level);
            if (order.getStatus() != OrderStatus.PENDING) {
                log.info("Ghi nhận rủi ro {} cho đơn #{} đang ở {}", level, orderId, order.getStatus());
                return;
            }
            if (requiresReview && props.fraudCheckEnabled()) {
                transition(order, OrderStatus.UNDER_REVIEW,
                        "Đơn cần xác minh (điểm rủi ro " + score + "): " + summary);
                return;
            }
            order.clearRisk();
            if (order.readyForPayment()) {
                transition(order, OrderStatus.AWAITING_PAYMENT, "Đã giữ hàng, chờ thanh toán");
            }
        });
    }

    /** Nhân viên đã xác minh đơn UNDER_REVIEW. */
    public void onRiskReviewed(long orderId, boolean approved, String reviewer, String note) {
        findOrder(orderId).ifPresent(order -> {
            if (order.getStatus() != OrderStatus.UNDER_REVIEW) {
                log.info("Bỏ qua RiskReviewed cho đơn #{} đang ở {}", orderId, order.getStatus());
                return;
            }
            String detail = note == null || note.isBlank() ? "" : ": " + note;
            if (!approved) {
                transition(order, OrderStatus.CANCELLED, "Đơn bị từ chối sau khi xác minh" + detail);
                return;
            }
            order.clearRisk();
            if (order.readyForPayment()) {
                transition(order, OrderStatus.AWAITING_PAYMENT, "Đã xác minh bởi " + reviewer + detail);
            } else {
                transition(order, OrderStatus.PENDING, "Đã xác minh bởi " + reviewer
                        + (order.isInventoryReserved() ? ", chờ định giá khuyến mãi" : ", chờ giữ hàng") + detail);
            }
        });
    }

    /**
     * Kết quả định giá khuyến mãi (Promotion Service): cập nhật giá flash sale + giảm giá voucher vào tổng tiền.
     * Đơn đang chờ xác minh rủi ro vẫn nhận giá mới nhưng chưa được yêu cầu thanh toán.
     */
    public void onPromotionApplied(long orderId, Map<Long, BigDecimal> salePrices, BigDecimal itemDiscount,
                                   BigDecimal voucherDiscount, String voucherCode, String summary) {
        findOrder(orderId).ifPresent(order -> {
            if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.UNDER_REVIEW) {
                log.info("Bỏ qua PromotionApplied cho đơn #{} đang ở {}", orderId, order.getStatus());
                return;
            }
            BigDecimal before = order.getTotalAmount();
            if (!order.applyPromotion(salePrices, itemDiscount, voucherDiscount, voucherCode, summary)) {
                return;
            }
            if (order.getDiscount().signum() > 0) {
                log.info("Đơn #{} được giảm {} ({}): {} → {}", orderId, money(order.getDiscount()), summary,
                        money(before), money(order.getTotalAmount()));
            }
            if (order.getStatus() == OrderStatus.PENDING && order.readyForPayment()) {
                transition(order, OrderStatus.AWAITING_PAYMENT, "Đã giữ hàng, chờ thanh toán");
            }
        });
    }

    /** Ưu đãi khách chọn không còn hợp lệ (hết suất flash sale, voucher hết lượt...) → hủy, không tính giá khác. */
    public void onPromotionRejected(long orderId, String reason) {
        findOrder(orderId).ifPresent(order -> {
            if (order.getStatus() == OrderStatus.PENDING || order.getStatus() == OrderStatus.UNDER_REVIEW) {
                transition(order, OrderStatus.CANCELLED, "Không áp dụng được khuyến mãi: " + reason);
            }
        });
    }

    public void onPaymentCompleted(long orderId) {
        findOrder(orderId).ifPresent(order -> {
            if (order.getStatus() == OrderStatus.AWAITING_PAYMENT) {
                transition(order, OrderStatus.CONFIRMED, "Thanh toán thành công");
            } else {
                // Đơn đã bị hủy (vd. timeout) trước khi có kết quả thanh toán:
                // Payment Service sẽ tự hoàn tiền khi nhận OrderCancelled.
                log.warn("PaymentCompleted đến muộn cho đơn #{} ({})", orderId, order.getStatus());
            }
        });
    }

    public void onPaymentFailed(long orderId, String reason) {
        findOrder(orderId).ifPresent(order -> {
            if (order.getStatus() == OrderStatus.AWAITING_PAYMENT) {
                transition(order, OrderStatus.CANCELLED, "Thanh toán thất bại: " + reason);
            }
        });
    }

    /** Vận đơn đổi trạng thái (Shipping Service): bàn giao/đang giao → SHIPPING, đã giao → DELIVERED. */
    public void onShipmentStatus(long orderId, String shipmentStatus, String carrier, String trackingNumber) {
        findOrder(orderId).ifPresent(order -> {
            String via = carrier == null ? "" : " qua " + carrier + (trackingNumber == null ? "" : ", mã vận đơn " + trackingNumber);
            boolean delivered = "DELIVERED".equals(shipmentStatus);
            if ((delivered || SHIPMENT_IN_TRANSIT.contains(shipmentStatus)) && order.getStatus() == OrderStatus.CONFIRMED) {
                transition(order, OrderStatus.SHIPPING, "Đã bàn giao cho đơn vị vận chuyển" + via);
            }
            if (delivered && order.getStatus() == OrderStatus.SHIPPING) {
                transition(order, OrderStatus.DELIVERED, "Giao hàng thành công" + via);
            }
        });
    }

    /** Saga timeout: đơn kẹt quá lâu → hủy (hành động bù). */
    @Transactional
    public void expire(long orderId) {
        findOrder(orderId).ifPresent(order -> {
            if (OrderStatus.IN_FLIGHT.contains(order.getStatus())) {
                transition(order, OrderStatus.CANCELLED, "Quá thời gian xử lý (" + props.processingTimeout() + ")");
            } else if (order.getStatus() == OrderStatus.UNDER_REVIEW) {
                transition(order, OrderStatus.CANCELLED, "Quá thời hạn xác minh rủi ro (" + props.reviewTimeout() + ")");
            }
        });
    }

    public List<Long> findExpiredOrderIds() {
        Instant now = Instant.now();
        List<Long> ids = new ArrayList<>(orders.findStuckOrderIds(OrderStatus.IN_FLIGHT,
                now.minus(props.processingTimeout()), PageRequest.of(0, 100)));
        ids.addAll(orders.findStuckOrderIds(Set.of(OrderStatus.UNDER_REVIEW),
                now.minus(props.reviewTimeout()), PageRequest.of(0, 100)));
        return ids;
    }

    // ------------------------------------------------------------------ Nội bộ

    /** Chuyển trạng thái + ghi lịch sử + phát sự kiện tương ứng (qua outbox). */
    private void transition(Order order, OrderStatus next, String note) {
        OrderStatus previous = order.transitionTo(next, note);
        history.save(new OrderStatusHistory(order.getId(), previous, next, note));
        String key = key(order);
        OrderStatusChanged changed = new OrderStatusChanged(order.getId(), order.getUserId(), previous.name(),
                next.name(), order.getTotalAmount(), note, lines(order), order.getShippingAddress());
        switch (next) {
            case AWAITING_PAYMENT -> outbox.publish(Topics.ORDER_EVENTS, EventTypes.PAYMENT_REQUESTED, key,
                    new PaymentRequested(order.getId(), order.getUserId(), order.getTotalAmount(),
                            order.getPaymentMethod()));
            case CONFIRMED -> {
                outbox.publish(Topics.ORDER_EVENTS, EventTypes.ORDER_CONFIRMED, key, changed);
                notifyCustomer(order, "ORDER_CONFIRMED", "Đơn hàng #" + order.getId() + " đã được xác nhận",
                        "Thanh toán " + money(order.getTotalAmount()) + " thành công. Cảm ơn bạn đã mua sắm!");
            }
            case CANCELLED -> {
                outbox.publish(Topics.ORDER_EVENTS, EventTypes.ORDER_CANCELLED, key, changed);
                notifyCustomer(order, "ORDER_CANCELLED", "Đơn hàng #" + order.getId() + " đã bị hủy", note);
            }
            case UNDER_REVIEW -> {
                outbox.publish(Topics.ORDER_EVENTS, EventTypes.ORDER_STATUS_CHANGED, key, changed);
                notifyCustomer(order, "ORDER_UNDER_REVIEW", "Đơn hàng #" + order.getId() + " đang được xác minh",
                        "Đơn hàng cần thêm bước xác minh an toàn trước khi thanh toán. Chúng tôi sẽ thông báo ngay khi hoàn tất.");
            }
            case PENDING -> outbox.publish(Topics.ORDER_EVENTS, EventTypes.ORDER_STATUS_CHANGED, key, changed);
            case SHIPPING, DELIVERED -> {
                outbox.publish(Topics.ORDER_EVENTS, EventTypes.ORDER_STATUS_CHANGED, key, changed);
                notifyCustomer(order, "ORDER_" + next.name(),
                        "Đơn hàng #" + order.getId() + (next == OrderStatus.SHIPPING ? " đang được giao" : " đã giao thành công"),
                        note);
            }
        }
    }

    private void notifyCustomer(Order order, String type, String title, String message) {
        outbox.publish(Topics.NOTIFICATION_EVENTS, EventTypes.NOTIFICATION_REQUESTED, "user-" + order.getUserId(),
                new NotificationRequested(order.getUserId(), null, type, title, message, "order:" + order.getId()));
    }

    private static boolean isStaff(AuthenticatedUser user) {
        return user.hasAnyRole(Roles.ADMIN, Roles.WAREHOUSE_MANAGER, Roles.ANALYST);
    }

    private static List<OrderLine> lines(Order order) {
        return order.getItems().stream()
                .map(i -> new OrderLine(i.getProductId(), i.getProductName(), i.getQuantity(), i.getEffectivePrice()))
                .toList();
    }

    private static String key(Order order) {
        return order.getId().toString();
    }

    private Order load(long orderId) {
        return orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Không tìm thấy đơn hàng"));
    }

    private Optional<Order> findOrder(long orderId) {
        Optional<Order> order = orders.findById(orderId);
        if (order.isEmpty()) {
            log.warn("Nhận sự kiện cho đơn #{} không tồn tại", orderId);
        }
        return order;
    }

    private static PageRequest pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    static String money(BigDecimal amount) {
        return NumberFormat.getNumberInstance(Locale.of("vi", "VN")).format(amount) + " ₫";
    }
}
