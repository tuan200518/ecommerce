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

import java.math.BigDecimal;
import java.time.Instant;

/** Ưu đãi đã được giữ cho một đơn – để hoàn suất/lượt khi đơn bị hủy và để thống kê hiệu quả khuyến mãi. */
@Entity
@Table(name = "redemptions", indexes = {
        @Index(name = "idx_redemption_order", columnList = "orderId"),
        @Index(name = "idx_redemption_time", columnList = "createdAt")
})
public class Redemption {

    public enum Kind {
        FLASH_SALE, VOUCHER
    }

    public enum Status {
        RESERVED, RELEASED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind;

    /** voucherId hoặc flashSaleItemId. */
    @Column(nullable = false)
    private Long refId;

    /** Mã voucher hoặc tên chiến dịch (để hiển thị/thống kê). */
    @Column(nullable = false, length = 150)
    private String label;

    private int quantity;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal discount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.RESERVED;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant releasedAt;

    protected Redemption() {
    }

    public Redemption(long orderId, long userId, Kind kind, long refId, String label, int quantity,
                      BigDecimal discount) {
        this.orderId = orderId;
        this.userId = userId;
        this.kind = kind;
        this.refId = refId;
        this.label = label;
        this.quantity = quantity;
        this.discount = discount;
        this.createdAt = Instant.now();
    }

    public void release() {
        this.status = Status.RELEASED;
        this.releasedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public Kind getKind() {
        return kind;
    }

    public Long getRefId() {
        return refId;
    }

    public String getLabel() {
        return label;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
