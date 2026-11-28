package com.shop.helpdesk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** Read-model đơn hàng (từ order-events): kiểm tra đơn thuộc về khách và hiển thị ngữ cảnh cho nhân viên CSKH. */
@Entity
@Table(name = "order_refs")
public class OrderRef {

    @Id
    private Long orderId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private Instant updatedAt;

    protected OrderRef() {
    }

    public OrderRef(long orderId, long userId) {
        this.orderId = orderId;
        this.userId = userId;
    }

    public void update(String status, BigDecimal totalAmount) {
        this.status = status;
        if (totalAmount != null) {
            this.totalAmount = totalAmount;
        }
        this.updatedAt = Instant.now();
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }
}
