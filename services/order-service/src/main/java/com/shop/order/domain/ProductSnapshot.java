package com.shop.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read-model cục bộ về sản phẩm, được cập nhật từ topic product-events.
 * Order Service dùng bảng này để chốt giá khi đặt hàng mà không cần gọi đồng bộ sang Product Service.
 */
@Entity
@Table(name = "product_snapshots")
public class ProductSnapshot {

    @Id
    private Long productId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal price;

    private boolean active;

    private Instant updatedAt;

    protected ProductSnapshot() {
    }

    public ProductSnapshot(long productId) {
        this.productId = productId;
    }

    public void update(String name, BigDecimal price, boolean active) {
        this.name = name;
        this.price = price;
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public Long getProductId() {
        return productId;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public boolean isActive() {
        return active;
    }
}
