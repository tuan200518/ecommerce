package com.shop.order.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Vòng đời đơn hàng:
 * <pre>
 * PENDING ──InventoryReserved──▶ AWAITING_PAYMENT ──PaymentCompleted──▶ CONFIRMED ──▶ SHIPPING ──▶ DELIVERED
 *    │                                │                                    │
 *    └──InventoryRejected/timeout──▶ CANCELLED ◀──PaymentFailed/timeout────┘ (hủy bởi khách/admin)
 * </pre>
 */
public enum OrderStatus {
    PENDING, AWAITING_PAYMENT, CONFIRMED, SHIPPING, DELIVERED, CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(AWAITING_PAYMENT, CANCELLED),
            AWAITING_PAYMENT, EnumSet.of(CONFIRMED, CANCELLED),
            CONFIRMED, EnumSet.of(SHIPPING, CANCELLED),
            SHIPPING, EnumSet.of(DELIVERED),
            DELIVERED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    public boolean canTransitionTo(OrderStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    /** Các trạng thái Saga đang chạy (chưa có kết quả giữ hàng/thanh toán). */
    public static final Set<OrderStatus> IN_FLIGHT = EnumSet.of(PENDING, AWAITING_PAYMENT);
}
