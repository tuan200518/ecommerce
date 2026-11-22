package com.shop.cart.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "cart_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_cart_product", columnNames = {"cart_id", "productId"}))
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id")
    private Cart cart;

    @Column(nullable = false)
    private long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private Instant addedAt;

    protected CartItem() {
    }

    CartItem(Cart cart, long productId, int quantity) {
        this.cart = cart;
        this.productId = productId;
        this.quantity = quantity;
        this.addedAt = Instant.now();
    }

    void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
