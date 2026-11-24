package com.shop.loyalty;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * @param amountPerPoint       số tiền (VND) cho 1 điểm cơ bản, trước khi nhân hệ số hạng
 * @param firstOrderBonus      thưởng nhiệm vụ "đơn hàng đầu tiên"
 * @param referralBonus        thưởng cho người giới thiệu khi người được giới thiệu có đơn đầu tiên hợp lệ
 * @param reviewBonus          thưởng mỗi đánh giá sản phẩm
 * @param maxReviewBonusPerDay giới hạn số lần thưởng đánh giá mỗi 24 giờ (chống lạm dụng)
 * @param notifyMaxAge         chỉ gửi thông báo cho sự kiện mới (không spam khi đọc lại lịch sử topic)
 * @param rewards              danh mục phần thưởng đổi điểm (mỗi phần thưởng = một voucher do Promotion Service phát)
 */
@ConfigurationProperties(prefix = "shop.loyalty")
public record LoyaltyProperties(BigDecimal amountPerPoint, Long firstOrderBonus, Long referralBonus, Long reviewBonus,
                                Integer maxReviewBonusPerDay, Duration notifyMaxAge, List<Reward> rewards) {

    /**
     * @param voucherType PERCENT (value = %) | FIXED (value = số tiền) | FREE_SHIPPING (value bỏ qua)
     * @param maxDiscount mức giảm tối đa cho PERCENT (null = không giới hạn)
     */
    public record Reward(String id, String name, Long points, String voucherType, BigDecimal value,
                         BigDecimal maxDiscount, BigDecimal minOrderAmount, Integer validDays) {

        public Reward {
            minOrderAmount = minOrderAmount == null ? BigDecimal.ZERO : minOrderAmount;
            value = value == null ? BigDecimal.ZERO : value;
            validDays = validDays == null ? 30 : validDays;
        }
    }

    public LoyaltyProperties {
        amountPerPoint = amountPerPoint == null ? new BigDecimal("10000") : amountPerPoint;
        firstOrderBonus = firstOrderBonus == null ? 200L : firstOrderBonus;
        referralBonus = referralBonus == null ? 500L : referralBonus;
        reviewBonus = reviewBonus == null ? 50L : reviewBonus;
        maxReviewBonusPerDay = maxReviewBonusPerDay == null ? 5 : maxReviewBonusPerDay;
        notifyMaxAge = notifyMaxAge == null ? Duration.ofMinutes(10) : notifyMaxAge;
        rewards = rewards == null || rewards.isEmpty() ? defaultRewards() : List.copyOf(rewards);
    }

    private static List<Reward> defaultRewards() {
        return List.of(
                new Reward("FREESHIP", "Miễn phí vận chuyển", 200L, "FREE_SHIPPING", null, null, null, 30),
                new Reward("FIXED_20K", "Voucher giảm 20.000 ₫ (đơn từ 100.000 ₫)", 300L, "FIXED",
                        new BigDecimal("20000"), null, new BigDecimal("100000"), 30),
                new Reward("PERCENT_10", "Giảm 10% tối đa 100.000 ₫ (đơn từ 300.000 ₫)", 800L, "PERCENT",
                        new BigDecimal("10"), new BigDecimal("100000"), new BigDecimal("300000"), 30),
                new Reward("FIXED_100K", "Voucher giảm 100.000 ₫ (đơn từ 1.000.000 ₫)", 1500L, "FIXED",
                        new BigDecimal("100000"), null, new BigDecimal("1000000"), 30));
    }
}
