package com.shop.common.event.payload;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Payload cho topic order-events. */
public final class OrderEvents {

    public record OrderLine(long productId, String productName, int quantity, BigDecimal unitPrice) {
        public BigDecimal lineTotal() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }

    /**
     * @param shippingAddress địa chỉ giao hàng (Fraud Service dùng để phát hiện địa chỉ dùng chung bất thường)
     * @param shippingFee     phí vận chuyển đã cộng vào totalAmount
     */
    public record OrderCreated(long orderId, long userId, List<OrderLine> items,
                               BigDecimal totalAmount, String paymentMethod, Instant createdAt,
                               String shippingAddress, BigDecimal shippingFee, String voucherCode) {

        public OrderCreated(long orderId, long userId, List<OrderLine> items, BigDecimal totalAmount,
                            String paymentMethod, Instant createdAt) {
            this(orderId, userId, items, totalAmount, paymentMethod, createdAt, null, null, null);
        }

        public OrderCreated(long orderId, long userId, List<OrderLine> items, BigDecimal totalAmount,
                            String paymentMethod, Instant createdAt, String shippingAddress, BigDecimal shippingFee) {
            this(orderId, userId, items, totalAmount, paymentMethod, createdAt, shippingAddress, shippingFee, null);
        }
    }

    /** Order Service (điều phối Saga) yêu cầu Payment Service thu tiền sau khi đã giữ hàng. */
    public record PaymentRequested(long orderId, long userId, BigDecimal amount, String paymentMethod) {
    }

    /**
     * Dùng cho OrderConfirmed / OrderCancelled / OrderStatusChanged.
     *
     * @param shippingAddress Shipping Service tạo vận đơn từ OrderConfirmed nên cần địa chỉ giao hàng
     */
    public record OrderStatusChanged(long orderId, long userId, String previousStatus, String status,
                                     BigDecimal totalAmount, String reason, List<OrderLine> items,
                                     String shippingAddress) {

        public OrderStatusChanged(long orderId, long userId, String previousStatus, String status,
                                  BigDecimal totalAmount, String reason, List<OrderLine> items) {
            this(orderId, userId, previousStatus, status, totalAmount, reason, items, null);
        }
    }

    private OrderEvents() {
    }
}
