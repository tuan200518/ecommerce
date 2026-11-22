package com.shop.cart.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** Read-model sản phẩm cục bộ của Cart Service, đồng bộ từ product-events. */
@Entity
@Table(name = "catalog_items")
public class CatalogItem {

    @Id
    private Long productId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal price;

    @Column(length = 1000)
    private String imageUrl;

    private boolean active;

    protected CatalogItem() {
    }

    public CatalogItem(long productId) {
        this.productId = productId;
    }

    public void update(String name, BigDecimal price, String imageUrl, boolean active) {
        this.name = name;
        this.price = price;
        this.imageUrl = imageUrl;
        this.active = active;
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

    public String getImageUrl() {
        return imageUrl;
    }

    public boolean isActive() {
        return active;
    }
}
