package com.shop.fraud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Một lần thanh toán thất bại (từ PaymentFailed) – khóa theo orderId vì mỗi đơn chỉ thanh toán một lần. */
@Entity
@Table(name = "payment_failures", indexes = @Index(name = "idx_payment_failures_user", columnList = "userId,occurredAt"))
public class PaymentFailure {

    @Id
    private Long orderId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Instant occurredAt;

    protected PaymentFailure() {
    }

    public PaymentFailure(long orderId, long userId, Instant occurredAt) {
        this.orderId = orderId;
        this.userId = userId;
        this.occurredAt = occurredAt;
    }
}
