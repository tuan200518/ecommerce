package com.shop.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Câu hỏi của khách về sản phẩm (Hỏi đáp). */
@Entity
@Table(name = "questions", indexes = {
        @Index(name = "idx_questions_product", columnList = "productId,createdAt"),
        @Index(name = "idx_questions_answered", columnList = "answered,createdAt")
})
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Long userId;

    @Column(length = 120)
    private String authorName;

    @Column(nullable = false, length = 1000)
    private String content;

    /** Đã có câu trả lời từ nhân viên. */
    private boolean answered;

    @Column(nullable = false)
    private Instant createdAt;

    protected Question() {
    }

    public Question(long productId, long userId, String authorName, String content) {
        this.productId = productId;
        this.userId = userId;
        this.authorName = authorName;
        this.content = content;
        this.createdAt = Instant.now();
    }

    public void markAnswered() {
        this.answered = true;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public String getContent() {
        return content;
    }

    public boolean isAnswered() {
        return answered;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
