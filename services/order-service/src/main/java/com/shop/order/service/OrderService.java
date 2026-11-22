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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final ProductSnapshotRepository products;
    private final OutboxService outbox;
    private final OrderProperties props;

    public OrderService(OrderRepository orders, OrderStatusHistoryRepository history,
                        ProductSnapshotRepository products, OutboxService outbox, OrderProperties props) {
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
        orders.save(order);
        history.save(new OrderStatusHistory(order.getId(), null, OrderStatus.PENDING, "Khách hàng tạo đơn"));

        outbox.publish(Topics.ORDER_EVENTS, EventTypes.ORDER_CREATED, key(order),
                new OrderCreated(order.getId(), order.getUserId(), lines(order), order.getTotalAmount(),
                        order.getPaymentMethod(), order.getCreatedAt()));
        notifyCustomer(order, "ORDER_CREATED", "Đã nhận đơn hàng #" + order.getId(),
                "Đơn hàng trị giá " + money(order.getTotalAmount()) + " đang được xử lý.");
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

    @Transactional
    public OrderDto updateStatusByStaff(AuthenticatedUser user, long orderId, UpdateStatusRequest req) {
        Access.requireAnyRole(user, Roles.ADMIN, Roles.WAREHOUSE_MANAGER);
        OrderStatus next;
        try {
            next = OrderStatus.valueOf(req.status());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Trạng thái không hợp lệ: " + req.status());
        }
        if (next == OrderStatus.AWAITING_PAYMENT || next == OrderStatus.CONFIRMED) {
            throw ApiException.badRequest("Trạng thái " + next + " do Saga tự động xử lý");
        }
        Order order = load(orderId);
        if (!order.getStatus().canTransitionTo(next)) {
            throw ApiException.conflict("Không thể chuyển từ " + order.getStatus() + " sang " + next);
        }
        String note = req.note() == null || req.note().isBlank() ? "Cập nhật bởi " + user.email() : req.note();
        transition(order, next, note);
        return OrderDto.from(order);
    }

    // ------------------------------------------------------------------ Truy vấn

    @Transactional(readOnly = true)
    public OrderDto getOrder(AuthenticatedUser user, long orderId) {
        Order order = load(orderId);
        boolean staff = user.hasAnyRole(Roles.ADMIN, Roles.WAREHOUSE_MANAGER, Roles.ANALYST);
        if (!staff && !order.getUserId().equals(user.userId())) {
            throw ApiException.notFound("Không tìm thấy đơn hàng");
        }
        return OrderDto.from(order, history.findByOrderIdOrderByIdAsc(orderId));
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
            return PageResponse.of(orders.findAllByOrderByCreatedAtDesc(pageable(page, size)), OrderDto::from);
        }
        try {
            return PageResponse.of(orders.findByStatusOrderByCreatedAtDesc(OrderStatus.valueOf(status),
                    pageable(page, size)), OrderDto::from);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Trạng thái không hợp lệ: " + status);
        }
    }

    // ------------------------------------------------------------------ Các bước Saga (gọi trong transaction của handler)

    /** Kho đã giữ hàng → yêu cầu thanh toán. */
    public void onInventoryReserved(long orderId) {
        findOrder(orderId).ifPresent(order -> {
            if (order.getStatus() != OrderStatus.PENDING) {
                log.info("Bỏ qua InventoryReserved cho đơn #{} đang ở {}", orderId, order.getStatus());
                return;
            }
            transition(order, OrderStatus.AWAITING_PAYMENT, "Đã giữ hàng, chờ thanh toán");
        });
    }

    public void onInventoryRejected(long orderId, String reason) {
        findOrder(orderId).ifPresent(order -> {
            if (order.getStatus() == OrderStatus.PENDING) {
                transition(order, OrderStatus.CANCELLED, "Không đủ tồn kho: " + reason);
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

    /** Saga timeout: đơn kẹt quá lâu ở PENDING/AWAITING_PAYMENT → hủy (hành động bù). */
    @Transactional
    public void expire(long orderId) {
        findOrder(orderId).ifPresent(order -> {
            if (OrderStatus.IN_FLIGHT.contains(order.getStatus())) {
                transition(order, OrderStatus.CANCELLED, "Quá thời gian xử lý (" + props.processingTimeout() + ")");
            }
        });
    }

    public List<Long> findExpiredOrderIds() {
        return orders.findStuckOrderIds(OrderStatus.IN_FLIGHT,
                java.time.Instant.now().minus(props.processingTimeout()), PageRequest.of(0, 100));
    }

    // ------------------------------------------------------------------ Nội bộ

    /** Chuyển trạng thái + ghi lịch sử + phát sự kiện tương ứng (qua outbox). */
    private void transition(Order order, OrderStatus next, String note) {
        OrderStatus previous = order.transitionTo(next, note);
        history.save(new OrderStatusHistory(order.getId(), previous, next, note));
        String key = key(order);
        OrderStatusChanged changed = new OrderStatusChanged(order.getId(), order.getUserId(), previous.name(),
                next.name(), order.getTotalAmount(), note, lines(order));
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
            case SHIPPING, DELIVERED -> {
                outbox.publish(Topics.ORDER_EVENTS, EventTypes.ORDER_STATUS_CHANGED, key, changed);
                notifyCustomer(order, "ORDER_" + next.name(),
                        "Đơn hàng #" + order.getId() + (next == OrderStatus.SHIPPING ? " đang được giao" : " đã giao thành công"),
                        note);
            }
            default -> {
            }
        }
    }

    private void notifyCustomer(Order order, String type, String title, String message) {
        outbox.publish(Topics.NOTIFICATION_EVENTS, EventTypes.NOTIFICATION_REQUESTED, "user-" + order.getUserId(),
                new NotificationRequested(order.getUserId(), null, type, title, message, "order:" + order.getId()));
    }

    private static List<OrderLine> lines(Order order) {
        return order.getItems().stream()
                .map(i -> new OrderLine(i.getProductId(), i.getProductName(), i.getQuantity(), i.getUnitPrice()))
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
