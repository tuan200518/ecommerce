package com.shop.promotion;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param maxEventAge     chỉ định giá cho OrderCreated mới hơn mức này. Khi service chạy lần đầu và đọc topic từ đầu,
 *                        các đơn cũ đã hoàn tất từ lâu → bỏ qua (không giữ suất/lượt voucher cho chúng)
 * @param seedEnabled     tạo voucher mẫu và một flash sale mẫu khi CSDL còn trống
 * @param maxFlashSaleDays thời lượng tối đa của một chiến dịch flash sale
 */
@ConfigurationProperties(prefix = "shop.promotion")
public record PromotionProperties(Duration maxEventAge, Boolean seedEnabled, Integer maxFlashSaleDays) {

    public PromotionProperties {
        maxEventAge = maxEventAge == null ? Duration.ofMinutes(10) : maxEventAge;
        seedEnabled = seedEnabled == null || seedEnabled;
        maxFlashSaleDays = maxFlashSaleDays == null ? 30 : maxFlashSaleDays;
    }
}
