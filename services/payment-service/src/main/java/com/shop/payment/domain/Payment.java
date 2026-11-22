package com.shop.payment.domain;

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
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payments",
        uniqueConstraints = @UniqueConstraint(name = "uk_payment_order", columnNames = "orderId"),
        indexes = @Index(name = "idx_payment_status", columnList = "status,createdAt"))
public class Payment {

    public enum Status { PENDING, SUCCEEDED, FAILED, REFUNDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mỗi đơn chỉ có một payment → ràng buộc unique là lớp chống trùng thứ hai (sau processed_events). */
    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 32)
    private String method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(length = 500)
    private String failureReason;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private long version;

    protected Payment() {
    }

    public Payment(long orderId, long userId, BigDecimal amount, String method) {
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.method = method;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void succeed() {
        this.status = Status.SUCCEEDED;
        this.updatedAt = Instant.now();
    }

    public void fail(String reason) {
        this.status = Status.FAILED;
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    public void refund() {
        this.status = Status.REFUNDED;
        this.updatedAt = Instant.now();
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

    public BigDecimal getAmount() {
        return amount;
    }

    public String getMethod() {
        return method;
    }

    public Status getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
