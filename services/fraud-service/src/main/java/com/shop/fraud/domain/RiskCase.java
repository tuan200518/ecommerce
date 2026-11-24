package com.shop.fraud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/** Hồ sơ rủi ro của một đơn hàng: điểm, mức, các tín hiệu (JSON) và kết quả xác minh. */
@Entity
@Table(name = "risk_cases", indexes = {
        @Index(name = "idx_risk_cases_status", columnList = "status,createdAt"),
        @Index(name = "idx_risk_cases_user", columnList = "userId")
})
public class RiskCase {

    public enum Status {
        /** Rủi ro thấp/trung bình – tự động chấp nhận. */
        AUTO_APPROVED,
        /** Chờ nhân viên xác minh. */
        PENDING_REVIEW,
        APPROVED,
        REJECTED,
        /** Đơn đã kết thúc (hủy/hết hạn) trước khi được xác minh. */
        CLOSED
    }

    @Id
    private Long orderId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    private int score;

    @Column(nullable = false, length = 16)
    private String level;

    @Column(nullable = false, length = 4000)
    private String signalsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;

    @Column(length = 255)
    private String reviewer;

    @Column(length = 500)
    private String reviewNote;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant decidedAt;

    @Version
    private long version;

    protected RiskCase() {
    }

    public RiskCase(long orderId, long userId, BigDecimal totalAmount, int score, String level,
                    String signalsJson, boolean requiresReview) {
        this.orderId = orderId;
        this.userId = userId;
        this.totalAmount = totalAmount;
        this.score = score;
        this.level = level;
        this.signalsJson = signalsJson;
        this.status = requiresReview ? Status.PENDING_REVIEW : Status.AUTO_APPROVED;
        this.createdAt = Instant.now();
        if (!requiresReview) {
            this.decidedAt = this.createdAt;
        }
    }

    public void decide(boolean approved, String reviewer, String note) {
        this.status = approved ? Status.APPROVED : Status.REJECTED;
        this.reviewer = reviewer;
        this.reviewNote = note;
        this.decidedAt = Instant.now();
    }

    public void close(String reason) {
        this.status = Status.CLOSED;
        this.reviewNote = reason;
        this.decidedAt = Instant.now();
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public int getScore() {
        return score;
    }

    public String getLevel() {
        return level;
    }

    public String getSignalsJson() {
        return signalsJson;
    }

    public Status getStatus() {
        return status;
    }

    public String getReviewer() {
        return reviewer;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
