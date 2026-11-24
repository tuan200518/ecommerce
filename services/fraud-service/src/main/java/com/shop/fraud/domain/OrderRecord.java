package com.shop.fraud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** Read-model tối giản về đơn hàng (từ order-events) để tính tần suất đặt/hủy và địa chỉ dùng chung. */
@Entity
@Table(name = "order_records", indexes = {
        @Index(name = "idx_order_records_user", columnList = "userId,createdAt"),
        @Index(name = "idx_order_records_address", columnList = "addressKey,createdAt")
})
public class OrderRecord {

    @Id
    private Long orderId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(length = 200)
    private String addressKey;

    @Column(nullable = false)
    private Instant createdAt;

    private boolean cancelled;

    protected OrderRecord() {
    }

    public OrderRecord(long orderId, long userId, BigDecimal totalAmount, String addressKey, Instant createdAt) {
        this.orderId = orderId;
        this.userId = userId;
        this.totalAmount = totalAmount;
        this.addressKey = addressKey;
        this.createdAt = createdAt;
    }

    public void markCancelled() {
        this.cancelled = true;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
