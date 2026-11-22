package com.shop.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Tồn kho của một sản phẩm.
 * <ul>
 *   <li>available: số lượng có thể bán.</li>
 *   <li>reserved: số lượng đang giữ cho các đơn chưa thanh toán.</li>
 * </ul>
 * Mọi thay đổi số lượng đi qua câu UPDATE có điều kiện trong {@link InventoryRepository}
 * để an toàn khi nhiều đơn tranh nhau cùng một sản phẩm.
 */
@Entity
@Table(name = "inventories")
public class Inventory {

    @Id
    private Long productId;

    @Column(nullable = false)
    private String productName;

    @Column(nullable = false)
    private int available;

    @Column(nullable = false)
    private int reserved;

    @Column(nullable = false)
    private int lowStockThreshold;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Inventory() {
    }

    public Inventory(long productId, String productName, int available, int lowStockThreshold) {
        this.productId = productId;
        this.productName = productName;
        this.available = available;
        this.reserved = 0;
        this.lowStockThreshold = lowStockThreshold;
        this.updatedAt = Instant.now();
    }

    public void rename(String name) {
        this.productName = name;
        this.updatedAt = Instant.now();
    }

    public void changeThreshold(int threshold) {
        this.lowStockThreshold = threshold;
        this.updatedAt = Instant.now();
    }

    public boolean isLowStock() {
        return available <= lowStockThreshold;
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public int getAvailable() {
        return available;
    }

    public int getReserved() {
        return reserved;
    }

    public int getLowStockThreshold() {
        return lowStockThreshold;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
