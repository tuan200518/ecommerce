package com.shop.promotion.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Chiến dịch flash sale: một khung giờ, nhiều sản phẩm với giá ưu đãi và số suất giới hạn. */
@Entity
@Table(name = "flash_sales", indexes = @Index(name = "idx_flash_sale_time", columnList = "startsAt,endsAt"))
public class FlashSale {

    public enum Status {
        UPCOMING, ACTIVE, ENDED, CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private Instant startsAt;

    @Column(nullable = false)
    private Instant endsAt;

    private boolean cancelled;

    @Column(length = 255)
    private String createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "flashSale", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<FlashSaleItem> items = new ArrayList<>();

    protected FlashSale() {
    }

    public FlashSale(String name, String description, Instant startsAt, Instant endsAt, String createdBy) {
        this.name = name;
        this.description = description;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public FlashSaleItem addItem(CatalogItem product, java.math.BigDecimal salePrice, int quota, int perUserLimit) {
        FlashSaleItem item = new FlashSaleItem(this, product.getProductId(), product.getName(), product.getPrice(),
                salePrice, quota, perUserLimit);
        items.add(item);
        return item;
    }

    public Status status(Instant now) {
        if (cancelled) {
            return Status.CANCELLED;
        }
        if (now.isBefore(startsAt)) {
            return Status.UPCOMING;
        }
        return now.isBefore(endsAt) ? Status.ACTIVE : Status.ENDED;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<FlashSaleItem> getItems() {
        return items;
    }
}
