package com.shop.loyalty.web;

import com.shop.loyalty.LoyaltyProperties;
import com.shop.loyalty.domain.PointTransaction;
import com.shop.loyalty.domain.Tier;

import java.math.BigDecimal;
import java.time.Instant;

public final class LoyaltyDtos {

    private LoyaltyDtos() {
    }

    public record TierDto(String code, String name, BigDecimal minSpend, BigDecimal multiplier) {
        public static TierDto from(Tier t) {
            return new TierDto(t.name(), t.displayName(), t.minSpend(), t.multiplier());
        }
    }

    /**
     * @param spend12Months   chi tiêu hợp lệ 12 tháng gần nhất (căn cứ xếp hạng)
     * @param amountToNextTier số tiền cần chi thêm để lên hạng kế tiếp (0 nếu đã ở hạng cao nhất)
     */
    public record AccountDto(long userId, long balance, long lifetimeEarned, TierDto tier, BigDecimal spend12Months,
                             TierDto nextTier, BigDecimal amountToNextTier, String referralCode, boolean referred,
                             long referralCount, boolean firstOrderCompleted) {
    }

    public record TransactionDto(long id, String type, long points, String referenceId, String description,
                                 boolean reversed, Instant createdAt) {
        public static TransactionDto from(PointTransaction t) {
            return new TransactionDto(t.getId(), t.getType().name(), t.getPoints(), t.getReferenceId(),
                    t.getDescription(), t.isReversed(), t.getCreatedAt());
        }
    }

    public record ReferralCheckDto(boolean valid, String message) {
    }

    public record RewardDto(String id, String name, long points, String voucherType, BigDecimal value,
                            BigDecimal maxDiscount, BigDecimal minOrderAmount, int validDays) {
        public static RewardDto from(LoyaltyProperties.Reward r) {
            return new RewardDto(r.id(), r.name(), r.points(), r.voucherType(), r.value(), r.maxDiscount(),
                    r.minOrderAmount(), r.validDays());
        }
    }

    public record RedeemResultDto(String redemptionId, String rewardName, long pointsSpent, long balance,
                                  String message) {
    }
}
