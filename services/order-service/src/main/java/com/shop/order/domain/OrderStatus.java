package com.shop.order.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Vòng đời đơn hàng:
 * <pre>
 *            ┌──RiskAssessed(HIGH)──▶ UNDER_REVIEW ──duyệt──┐
 *            │                            │ từ chối/hết hạn  │
 * PENDING ───┴─(đã giữ hàng + rủi ro thấp)─────────────────┴──▶ AWAITING_PAYMENT ──PaymentCompleted──▶ CONFIRMED
 *    │                                                                │                                   │
 *    └──InventoryRejected/timeout──▶ CANCELLED ◀──PaymentFailed/timeout┘    (hủy bởi khách/admin) ◀────────┤
 *                                                                                                         ▼
 *                                              Shipping Service: bàn giao vận chuyển ──▶ SHIPPING ──▶ DELIVERED
 * </pre>
 * Đơn chỉ chuyển sang AWAITING_PAYMENT khi đủ HAI điều kiện: Inventory đã giữ hàng và Fraud Service đánh giá
 * rủi ro chấp nhận được (hoặc nhân viên đã duyệt).
 */
public enum OrderStatus {
    PENDING, UNDER_REVIEW, AWAITING_PAYMENT, CONFIRMED, SHIPPING, DELIVERED, CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(AWAITING_PAYMENT, UNDER_REVIEW, CANCELLED),
            UNDER_REVIEW, EnumSet.of(PENDING, AWAITING_PAYMENT, CANCELLED),
            AWAITING_PAYMENT, EnumSet.of(CONFIRMED, CANCELLED),
            CONFIRMED, EnumSet.of(SHIPPING, CANCELLED),
            SHIPPING, EnumSet.of(DELIVERED),
            DELIVERED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    public boolean canTransitionTo(OrderStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    /** Các trạng thái Saga đang chạy tự động (chưa có kết quả giữ hàng/rủi ro/thanh toán). */
    public static final Set<OrderStatus> IN_FLIGHT = EnumSet.of(PENDING, AWAITING_PAYMENT);
}
