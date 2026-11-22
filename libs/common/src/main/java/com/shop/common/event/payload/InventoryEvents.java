package com.shop.common.event.payload;

import java.util.List;

/** Payload cho topic inventory-events. */
public final class InventoryEvents {

    public record ReservedLine(long productId, int quantity) {
    }

    public record InventoryReserved(long orderId, List<ReservedLine> items) {
    }

    public record InventoryRejected(long orderId, Long productId, String reason) {
    }

    /** Dùng cho InventoryCommitted / InventoryReleased. */
    public record ReservationSettled(long orderId, List<ReservedLine> items) {
    }

    public record StockUpdated(long productId, String productName, int available, int reserved,
                               int lowStockThreshold) {
    }

    public record StockLow(long productId, String productName, int available, int threshold) {
    }

    private InventoryEvents() {
    }
}
