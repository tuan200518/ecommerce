package com.shop.shipping.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Vòng đời vận đơn:
 * <pre>
 * CREATED (chờ đóng gói) → PACKED → HANDED_OVER (bàn giao ĐVVC) → IN_TRANSIT → DELIVERED
 *    └────────┴────────────────┴─────────────────┴──→ CANCELLED (đơn hàng bị hủy)
 * </pre>
 * Order Service chuyển đơn sang SHIPPING khi vận đơn tới HANDED_OVER/IN_TRANSIT và DELIVERED khi giao xong.
 */
public enum ShipmentStatus {
    CREATED, PACKED, HANDED_OVER, IN_TRANSIT, DELIVERED, CANCELLED;

    private static final Map<ShipmentStatus, Set<ShipmentStatus>> ALLOWED = Map.of(
            CREATED, EnumSet.of(PACKED, CANCELLED),
            PACKED, EnumSet.of(HANDED_OVER, CANCELLED),
            HANDED_OVER, EnumSet.of(IN_TRANSIT, DELIVERED, CANCELLED),
            IN_TRANSIT, EnumSet.of(DELIVERED, CANCELLED),
            DELIVERED, EnumSet.noneOf(ShipmentStatus.class),
            CANCELLED, EnumSet.noneOf(ShipmentStatus.class));

    public boolean canTransitionTo(ShipmentStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    /** Các bước nhân viên được thao tác (hủy vận đơn chỉ xảy ra khi đơn hàng bị hủy). */
    public Set<ShipmentStatus> staffNext() {
        EnumSet<ShipmentStatus> next = EnumSet.noneOf(ShipmentStatus.class);
        next.addAll(ALLOWED.get(this));
        next.remove(CANCELLED);
        return next;
    }

    /** Đã rời kho (đơn vị vận chuyển đang giữ hàng) – không đổi đơn vị vận chuyển được nữa. */
    public boolean leftWarehouse() {
        return this == HANDED_OVER || this == IN_TRANSIT || this == DELIVERED;
    }

    public boolean isFinal() {
        return this == DELIVERED || this == CANCELLED;
    }
}
