package com.shop.cart.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Entity
@Table(name = "carts")
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<CartItem> items = new ArrayList<>();

    protected Cart() {
    }

    public Cart(long userId) {
        this.userId = userId;
        this.updatedAt = Instant.now();
    }

    public Optional<CartItem> find(long productId) {
        return items.stream().filter(i -> i.getProductId() == productId).findFirst();
    }

    /** @return số lượng mới của sản phẩm trong giỏ. */
    public int add(long productId, int quantity) {
        touch();
        CartItem item = find(productId).orElse(null);
        if (item == null) {
            items.add(new CartItem(this, productId, quantity));
            return quantity;
        }
        item.setQuantity(item.getQuantity() + quantity);
        return item.getQuantity();
    }

    public boolean setQuantity(long productId, int quantity) {
        touch();
        return find(productId).map(i -> {
            i.setQuantity(quantity);
            return true;
        }).orElse(false);
    }

    public boolean remove(long productId) {
        touch();
        return items.removeIf(i -> i.getProductId() == productId);
    }

    public void clear() {
        touch();
        items.clear();
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<CartItem> getItems() {
        return items;
    }
}
