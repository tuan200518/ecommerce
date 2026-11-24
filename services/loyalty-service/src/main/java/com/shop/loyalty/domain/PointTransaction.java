package com.shop.loyalty.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Sổ cái điểm thưởng. Ràng buộc duy nhất (userId, type, referenceId) bảo đảm mỗi đơn/đánh giá chỉ được thưởng
 * một lần dù sự kiện bị gửi lại – lớp bảo vệ thứ hai bên cạnh processed_events.
 */
@Entity
@Table(name = "point_transactions",
        uniqueConstraints = @UniqueConstraint(name = "uk_point_tx_ref", columnNames = {"userId", "type", "referenceId"}),
        indexes = {
                @Index(name = "idx_point_tx_user", columnList = "userId,createdAt"),
                @Index(name = "idx_point_tx_ref", columnList = "referenceId")
        })
public class PointTransaction {

    public enum Type {
        /** Tích điểm từ đơn hàng hợp lệ. */
        EARN,
        /** Thưởng nhiệm vụ đơn hàng đầu tiên. */
        FIRST_ORDER_BONUS,
        /** Thưởng giới thiệu bạn bè. */
        REFERRAL_BONUS,
        /** Thưởng viết đánh giá. */
        REVIEW_BONUS,
        /** Trừ lại điểm khi đơn bị hủy/hoàn hoặc đánh giá bị ẩn. */
        REVERSAL,
        /** Khách đổi điểm lấy phần thưởng (âm). Không bị trừ lại khi đơn hủy: voucher đã phát vẫn còn giá trị. */
        REDEMPTION
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Type type;

    /** Âm với REVERSAL. */
    private long points;

    /** Chi tiêu tính vào hạng (dương với EARN, âm với REVERSAL của EARN, 0 với thưởng). */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal spendAmount;

    @Column(nullable = false, length = 64)
    private String referenceId;

    @Column(length = 255)
    private String description;

    private boolean reversed;

    @Column(nullable = false)
    private Instant createdAt;

    protected PointTransaction() {
    }

    /** @param at thời điểm phát sinh nghiệp vụ (vd. lúc đơn được xác nhận) – dùng để tính hạng theo 12 tháng. */
    public PointTransaction(long userId, Type type, long points, BigDecimal spendAmount, String referenceId,
                            String description, Instant at) {
        this.userId = userId;
        this.type = type;
        this.points = points;
        this.spendAmount = spendAmount;
        this.referenceId = referenceId;
        this.description = description;
        this.createdAt = at == null ? Instant.now() : at;
    }

    public void markReversed() {
        this.reversed = true;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Type getType() {
        return type;
    }

    public long getPoints() {
        return points;
    }

    public BigDecimal getSpendAmount() {
        return spendAmount;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public String getDescription() {
        return description;
    }

    public boolean isReversed() {
        return reversed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
