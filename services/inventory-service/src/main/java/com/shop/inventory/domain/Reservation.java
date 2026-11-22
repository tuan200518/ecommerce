package com.shop.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Ghi nhận số lượng đã giữ cho từng (đơn hàng, sản phẩm) để có thể xuất kho hoặc hoàn kho chính xác. */
@Entity
@Table(name = "inventory_reservations",
        uniqueConstraints = @UniqueConstraint(name = "uk_reservation_order_product", columnNames = {"orderId", "productId"}))
public class Reservation {

    public enum Status { RESERVED, COMMITTED, RELEASED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    protected Reservation() {
    }

    public Reservation(long orderId, long productId, int quantity) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.status = Status.RESERVED;
        this.createdAt = Instant.now();
    }

    public void mark(Status next) {
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public Status getStatus() {
        return status;
    }
}
