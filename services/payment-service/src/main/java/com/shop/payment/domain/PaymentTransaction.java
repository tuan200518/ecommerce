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

import java.math.BigDecimal;
import java.time.Instant;

/** Từng lần gọi "cổng thanh toán" mô phỏng (thu tiền / hoàn tiền). */
@Entity
@Table(name = "payment_transactions", indexes = @Index(name = "idx_ptx_payment", columnList = "paymentId"))
public class PaymentTransaction {

    public enum Type { CHARGE, REFUND }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Type type;

    @Column(nullable = false)
    private boolean success;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(length = 64)
    private String providerRef;

    @Column(length = 500)
    private String message;

    @Column(nullable = false)
    private Instant createdAt;

    protected PaymentTransaction() {
    }

    public PaymentTransaction(long paymentId, Type type, boolean success, BigDecimal amount,
                              String providerRef, String message) {
        this.paymentId = paymentId;
        this.type = type;
        this.success = success;
        this.amount = amount;
        this.providerRef = providerRef;
        this.message = message;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

    public boolean isSuccess() {
        return success;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
