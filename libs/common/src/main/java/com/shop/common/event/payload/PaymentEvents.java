package com.shop.common.event.payload;

import java.math.BigDecimal;

/** Payload cho topic payment-events (PaymentCompleted / PaymentFailed / PaymentRefunded). */
public final class PaymentEvents {

    public record PaymentResult(long paymentId, long orderId, long userId, BigDecimal amount,
                                String method, String status, String reason) {
    }

    private PaymentEvents() {
    }
}
