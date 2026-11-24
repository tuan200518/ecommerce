package com.shop.common.event.payload;

import java.math.BigDecimal;

/** Payload cho topic shipping-events (ShipmentCreated / ShipmentStatusChanged / ShipmentDelivered). */
public final class ShippingEvents {

    public record ShipmentChanged(long shipmentId, long orderId, long userId, String carrier, String trackingNumber,
                                  String previousStatus, String status, BigDecimal fee, String note) {
    }

    private ShippingEvents() {
    }
}
