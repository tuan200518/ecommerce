package com.shop.promotion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** Read-model sản phẩm (từ product-events) – dùng để kiểm tra giá flash sale và báo giá cho khách. */
@Entity
@Table(name = "catalog_items")
public class CatalogItem {

    @Id
    private Long productId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal price;

    private Long categoryId;

    @Column(length = 500)
    private String imageUrl;

    private boolean active;

    @Column(nullable = false)
    private Instant updatedAt;

    protected CatalogItem() {
    }

    public CatalogItem(long productId) {
        this.productId = productId;
    }

    public void update(String name, BigDecimal price, Long categoryId, String imageUrl, boolean active) {
        this.name = name;
        this.price = price;
        this.categoryId = categoryId;
        this.imageUrl = imageUrl;
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

    public Long getCategoryId() {
        return categoryId;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public boolean isActive() {
        return active;
    }
}
