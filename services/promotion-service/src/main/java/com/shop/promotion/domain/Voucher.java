package com.shop.promotion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Mã giảm giá. {@code ownerUserId = null} → mã công khai; khác null → mã cá nhân (vd. đổi từ điểm Loyalty).
 * {@code used} chỉ thay đổi qua câu UPDATE có điều kiện (không vượt usageLimit).
 */
@Entity
@Table(name = "vouchers",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_voucher_code", columnNames = "code"),
                @UniqueConstraint(name = "uk_voucher_source_ref", columnNames = "sourceRef")
        },
        indexes = @Index(name = "idx_voucher_owner", columnList = "ownerUserId"))
public class Voucher {

    public enum Type {
        /** Giảm theo % giá trị hàng (có thể giới hạn mức giảm tối đa). */
        PERCENT,
        /** Giảm số tiền cố định. */
        FIXED,
        /** Miễn phí vận chuyển. */
        FREE_SHIPPING
    }

    public enum Source {
        ADMIN, LOYALTY, SYSTEM
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Type type;

    /** % với PERCENT, số tiền với FIXED, 0 với FREE_SHIPPING. (Tên cột tránh từ khóa VALUE của SQL.) */
    @Column(name = "discount_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal value;

    @Column(precision = 14, scale = 2)
    private BigDecimal maxDiscount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal minOrderAmount;

    @Column(nullable = false)
    private Instant startsAt;

    @Column(nullable = false)
    private Instant endsAt;

    /** Tổng số lượt dùng tối đa (null = không giới hạn). */
    private Integer usageLimit;

    private int used;

    private int perUserLimit;

    private Long ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;

    /** Tham chiếu nguồn phát hành (vd. redemptionId của Loyalty) → phát voucher đúng một lần. */
    @Column(length = 64)
    private String sourceRef;

    private boolean active = true;

    @Column(length = 255)
    private String createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    protected Voucher() {
    }

    public Voucher(String code, String description, Type type, BigDecimal value, BigDecimal maxDiscount,
                   BigDecimal minOrderAmount, Instant startsAt, Instant endsAt, Integer usageLimit, int perUserLimit,
                   Long ownerUserId, Source source, String sourceRef, String createdBy) {
        this.code = code;
        this.description = description;
        this.type = type;
        this.value = value;
        this.maxDiscount = maxDiscount;
        this.minOrderAmount = minOrderAmount == null ? BigDecimal.ZERO : minOrderAmount;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.usageLimit = usageLimit;
        this.perUserLimit = perUserLimit;
        this.ownerUserId = ownerUserId;
        this.source = source;
        this.sourceRef = sourceRef;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public boolean isExhausted() {
        return usageLimit != null && used >= usageLimit;
    }

    public Integer remainingUses() {
        return usageLimit == null ? null : Math.max(0, usageLimit - used);
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public Type getType() {
        return type;
    }

    public BigDecimal getValue() {
        return value;
    }

    public BigDecimal getMaxDiscount() {
        return maxDiscount;
    }

    public BigDecimal getMinOrderAmount() {
        return minOrderAmount;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public Integer getUsageLimit() {
        return usageLimit;
    }

    public int getUsed() {
        return used;
    }

    public int getPerUserLimit() {
        return perUserLimit;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public Source getSource() {
        return source;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public boolean isActive() {
        return active;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
