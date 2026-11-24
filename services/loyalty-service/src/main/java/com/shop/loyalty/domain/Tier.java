package com.shop.loyalty.domain;

import java.math.BigDecimal;

/** Hạng thành viên theo tổng chi tiêu hợp lệ trong 12 tháng gần nhất. Hạng cao nhận hệ số điểm cao hơn. */
public enum Tier {
    BRONZE("Đồng", new BigDecimal("0"), new BigDecimal("1.0")),
    SILVER("Bạc", new BigDecimal("5000000"), new BigDecimal("1.2")),
    GOLD("Vàng", new BigDecimal("20000000"), new BigDecimal("1.5")),
    PLATINUM("Bạch kim", new BigDecimal("50000000"), new BigDecimal("2.0"));

    private final String displayName;
    private final BigDecimal minSpend;
    private final BigDecimal multiplier;

    Tier(String displayName, BigDecimal minSpend, BigDecimal multiplier) {
        this.displayName = displayName;
        this.minSpend = minSpend;
        this.multiplier = multiplier;
    }

    public String displayName() {
        return displayName;
    }

    public BigDecimal minSpend() {
        return minSpend;
    }

    public BigDecimal multiplier() {
        return multiplier;
    }

    public static Tier forSpend(BigDecimal spend) {
        Tier result = BRONZE;
        for (Tier t : values()) {
            if (spend.compareTo(t.minSpend) >= 0) {
                result = t;
            }
        }
        return result;
    }

    public Tier next() {
        return this == PLATINUM ? null : values()[ordinal() + 1];
    }
}
