package com.shop.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Đánh giá sản phẩm: mỗi người mua chỉ đánh giá một lần cho mỗi sản phẩm. */
@Entity
@Table(name = "reviews",
        uniqueConstraints = @UniqueConstraint(name = "uk_review_user_product", columnNames = {"userId", "productId"}),
        indexes = @Index(name = "idx_reviews_product", columnList = "productId,visible,createdAt"))
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long productId;

    @Column(length = 255)
    private String productName;

    @Column(nullable = false)
    private Long userId;

    @Column(length = 120)
    private String authorName;

    private Long orderId;

    private int rating;

    @Column(length = 2000)
    private String comment;

    /** URL ảnh, mỗi dòng một URL. */
    @Column(length = 2600)
    private String images;

    private boolean visible = true;

    @Column(length = 300)
    private String moderationNote;

    @Column(nullable = false)
    private Instant createdAt;

    protected Review() {
    }

    public Review(long productId, String productName, long userId, String authorName, Long orderId, int rating,
                  String comment, List<String> images) {
        this.productId = productId;
        this.productName = productName;
        this.userId = userId;
        this.authorName = authorName;
        this.orderId = orderId;
        this.rating = rating;
        this.comment = comment;
        this.images = images == null || images.isEmpty() ? null : String.join("\n", images);
        this.createdAt = Instant.now();
    }

    public void setVisible(boolean visible, String note) {
        this.visible = visible;
        this.moderationNote = note;
    }

    public List<String> imageList() {
        return images == null || images.isBlank() ? List.of() : Arrays.asList(images.split("\n"));
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public Long getUserId() {
        return userId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public int getRating() {
        return rating;
    }

    public String getComment() {
        return comment;
    }

    public boolean isVisible() {
        return visible;
    }

    public String getModerationNote() {
        return moderationNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
