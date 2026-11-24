package com.shop.shipping.domain;

import java.math.BigDecimal;

/** Đơn vị vận chuyển (mô phỏng). Phí là chi phí cửa hàng trả cho đơn vị vận chuyển. */
public enum Carrier {
    GHN("Giao Hàng Nhanh", new BigDecimal("32000")),
    GHTK("Giao Hàng Tiết Kiệm", new BigDecimal("25000")),
    VTP("Viettel Post", new BigDecimal("28000"));

    private final String displayName;
    private final BigDecimal baseFee;

    Carrier(String displayName, BigDecimal baseFee) {
        this.displayName = displayName;
        this.baseFee = baseFee;
    }

    public String displayName() {
        return displayName;
    }

    public BigDecimal baseFee() {
        return baseFee;
    }

    /** Mã vận đơn: duy nhất vì mỗi đơn hàng chỉ có một vận đơn. */
    public String trackingNumberFor(long orderId) {
        return name() + String.format("%09d", orderId);
    }

    /** Chọn mặc định: đơn giá trị cao đi GHN (nhanh, bảo hiểm), còn lại chia đều GHTK / Viettel Post. */
    public static Carrier defaultFor(long orderId, BigDecimal orderTotal) {
        if (orderTotal != null && orderTotal.compareTo(new BigDecimal("10000000")) >= 0) {
            return GHN;
        }
        return orderId % 2 == 0 ? GHTK : VTP;
    }
}
