package com.shop.loyalty.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

/** Tài khoản thành viên: số dư điểm (đã trừ các lần hoàn), hạng, mã giới thiệu. */
@Entity
@Table(name = "loyalty_accounts",
        uniqueConstraints = @UniqueConstraint(name = "uk_loyalty_referral_code", columnNames = "referralCode"))
public class LoyaltyAccount {

    @Id
    private Long userId;

    private long balance;

    /** Tổng điểm từng được cộng (không trừ hoàn) – để thống kê. */
    private long lifetimeEarned;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Tier tier = Tier.BRONZE;

    @Column(nullable = false, length = 32)
    private String referralCode;

    /** Người đã giới thiệu tài khoản này (null nếu không có). */
    private Long referredBy;

    /** Đã nhận thưởng nhiệm vụ "đơn đầu tiên". */
    private boolean firstOrderRewarded;

    /** Người giới thiệu đã được thưởng nhờ đơn đầu tiên của tài khoản này. */
    private boolean referralRewarded;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected LoyaltyAccount() {
    }

    public LoyaltyAccount(long userId, String referralCode) {
        this.userId = userId;
        this.referralCode = referralCode;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void apply(long points) {
        balance += points;
        if (points > 0) {
            lifetimeEarned += points;
        }
        updatedAt = Instant.now();
    }

    /** @return hạng cũ */
    public Tier changeTier(Tier next) {
        Tier previous = tier;
        tier = next;
        updatedAt = Instant.now();
        return previous;
    }

    public void setReferredBy(long referrerId) {
        this.referredBy = referrerId;
    }

    public void setFirstOrderRewarded(boolean value) {
        this.firstOrderRewarded = value;
    }

    public void setReferralRewarded(boolean value) {
        this.referralRewarded = value;
    }

    public Long getUserId() {
        return userId;
    }

    public long getBalance() {
        return balance;
    }

    public long getLifetimeEarned() {
        return lifetimeEarned;
    }

    public Tier getTier() {
        return tier;
    }

    public String getReferralCode() {
        return referralCode;
    }

    public Long getReferredBy() {
        return referredBy;
    }

    public boolean isFirstOrderRewarded() {
        return firstOrderRewarded;
    }

    public boolean isReferralRewarded() {
        return referralRewarded;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
