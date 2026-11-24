package com.shop.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * @param processingTimeout     đơn kẹt ở PENDING/AWAITING_PAYMENT quá lâu → hủy
 * @param reviewTimeout         đơn chờ nhân viên xác minh rủi ro (UNDER_REVIEW) quá lâu → hủy, hoàn kho
 * @param fraudCheckEnabled     false → bỏ qua bước chấm điểm rủi ro (chạy không cần fraud-service)
 * @param freeShippingThreshold đơn từ mức này trở lên được miễn phí vận chuyển
 * @param shippingFee           phí vận chuyển cố định cho đơn dưới ngưỡng
 * @param promotionCheckEnabled false → không chờ Promotion Service định giá (chạy không cần promotion-service;
 *                              khi đó không nhận mã giảm giá)
 */
@ConfigurationProperties(prefix = "shop.order")
public record OrderProperties(Duration processingTimeout, Integer maxQuantityPerItem, Duration reviewTimeout,
                              Boolean fraudCheckEnabled, BigDecimal freeShippingThreshold, BigDecimal shippingFee,
                              Boolean promotionCheckEnabled) {

    public OrderProperties {
        if (processingTimeout == null) {
            processingTimeout = Duration.ofMinutes(2);
        }
        if (maxQuantityPerItem == null) {
            maxQuantityPerItem = 20;
        }
        if (reviewTimeout == null) {
            reviewTimeout = Duration.ofMinutes(30);
        }
        if (fraudCheckEnabled == null) {
            fraudCheckEnabled = true;
        }
        if (freeShippingThreshold == null) {
            freeShippingThreshold = new BigDecimal("500000");
        }
        if (shippingFee == null) {
            shippingFee = new BigDecimal("30000");
        }
        if (promotionCheckEnabled == null) {
            promotionCheckEnabled = true;
        }
    }

    public BigDecimal shippingFeeFor(BigDecimal subtotal) {
        return subtotal.compareTo(freeShippingThreshold) >= 0 ? BigDecimal.ZERO : shippingFee;
    }
}
