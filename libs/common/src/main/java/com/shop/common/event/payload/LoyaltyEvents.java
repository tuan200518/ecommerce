package com.shop.common.event.payload;

import java.math.BigDecimal;

/** Payload cho topic loyalty-events. */
public final class LoyaltyEvents {

    /** PointsEarned / PointsReversed: points là số điểm thay đổi (âm khi trừ), balance là số dư sau thay đổi. */
    public record PointsChanged(long userId, String type, long points, long balance, String tier,
                                String referenceId, String description) {
    }

    public record TierChanged(long userId, String previousTier, String tier) {
    }

    /**
     * Khách đổi điểm lấy phần thưởng. Loyalty Service sở hữu danh mục phần thưởng nên gửi kèm đầy đủ
     * thông số voucher; Promotion Service phát voucher cá nhân theo đúng thông số đó (idempotent theo redemptionId).
     *
     * @param voucherType PERCENT | FIXED | FREE_SHIPPING
     */
    public record PointsRedeemed(long userId, String redemptionId, String rewardId, String rewardName, long points,
                                 long balance, String voucherType, BigDecimal voucherValue, BigDecimal maxDiscount,
                                 BigDecimal minOrderAmount, int validDays) {
    }

    private LoyaltyEvents() {
    }
}
