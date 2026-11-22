package com.shop.common.event.payload;

import java.math.BigDecimal;

/** Payload cho topic cart-events. */
public final class CartEvents {

    public record CartItemChanged(long userId, long productId, String productName,
                                  int quantity, BigDecimal unitPrice) {
    }

    public record CartCleared(long userId, String reason) {
    }

    private CartEvents() {
    }
}
