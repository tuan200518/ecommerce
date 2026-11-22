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

    public record OrderCreated(long orderId, long userId, List<OrderLine> items,
                               BigDecimal totalAmount, String paymentMethod, Instant createdAt) {
    }

    /** Order Service (điều phối Saga) yêu cầu Payment Service thu tiền sau khi đã giữ hàng. */
    public record PaymentRequested(long orderId, long userId, BigDecimal amount, String paymentMethod) {
    }

    /** Dùng cho OrderConfirmed / OrderCancelled / OrderStatusChanged. */
    public record OrderStatusChanged(long orderId, long userId, String previousStatus, String status,
                                     BigDecimal totalAmount, String reason, List<OrderLine> items) {
    }

    private OrderEvents() {
    }
}
