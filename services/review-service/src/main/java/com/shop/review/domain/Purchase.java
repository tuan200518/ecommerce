package com.shop.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Read-model "ai đã mua gì" từ order-events: tạo khi đơn được xác nhận, đánh dấu delivered khi giao xong.
 * Chỉ người có purchase đã giao mới được đánh giá sản phẩm (người mua hợp lệ).
 */
@Entity
@Table(name = "purchases",
        uniqueConstraints = @UniqueConstraint(name = "uk_purchase_order_product", columnNames = {"orderId", "productId"}),
        indexes = @Index(name = "idx_purchases_user_product", columnList = "userId,productId"))
public class Purchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long productId;

    @Column(length = 255)
    private String productName;

    private boolean delivered;

    protected Purchase() {
    }

    public Purchase(long orderId, long userId, long productId, String productName) {
        this.orderId = orderId;
        this.userId = userId;
        this.productId = productId;
        this.productName = productName;
    }

    public void markDelivered() {
        this.delivered = true;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getProductName() {
        return productName;
    }

    public boolean isDelivered() {
        return delivered;
    }
}
