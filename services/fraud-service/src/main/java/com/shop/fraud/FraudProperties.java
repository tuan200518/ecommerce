package com.shop.fraud;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Ngưỡng của các luật chấm điểm rủi ro. Đây là giá trị khởi đầu để thử nghiệm (rule-based, dễ giải thích),
 * có thể chỉnh qua application.yml mà không cần sửa mã.
 *
 * @param reviewScore  điểm từ mức này trở lên VÀ có ít nhất {@code minSignalsForReview} tín hiệu → cần xác minh
 * @param mediumScore  điểm từ mức này → MEDIUM (chỉ ghi nhận, vẫn tự động chấp nhận)
 * @param maxEventAge  OrderCreated cũ hơn mức này (vd. khi đọc lại lịch sử topic) chỉ được lưu làm dữ liệu thống kê,
 *                     không chấm điểm/không phát sự kiện – Saga của đơn đó đã kết thúc (timeout) từ lâu
 */
@ConfigurationProperties(prefix = "shop.fraud")
public record FraudProperties(
        Integer reviewScore,
        Integer mediumScore,
        Integer minSignalsForReview,
        BigDecimal highValueAmount,
        BigDecimal veryHighValueAmount,
        Integer velocityOrders,
        Duration velocityWindow,
        Integer cancelledOrders,
        Duration cancelWindow,
        Integer paymentFailures,
        Duration paymentFailureWindow,
        Duration newAccountAge,
        BigDecimal newAccountAmount,
        Integer bulkQuantity,
        Integer sharedAddressUsers,
        Duration sharedAddressWindow,
        Duration maxEventAge) {

    public FraudProperties {
        reviewScore = reviewScore == null ? 70 : reviewScore;
        mediumScore = mediumScore == null ? 40 : mediumScore;
        minSignalsForReview = minSignalsForReview == null ? 2 : minSignalsForReview;
        highValueAmount = highValueAmount == null ? new BigDecimal("50000000") : highValueAmount;
        veryHighValueAmount = veryHighValueAmount == null ? new BigDecimal("100000000") : veryHighValueAmount;
        velocityOrders = velocityOrders == null ? 4 : velocityOrders;
        velocityWindow = velocityWindow == null ? Duration.ofMinutes(10) : velocityWindow;
        cancelledOrders = cancelledOrders == null ? 3 : cancelledOrders;
        cancelWindow = cancelWindow == null ? Duration.ofHours(24) : cancelWindow;
        paymentFailures = paymentFailures == null ? 2 : paymentFailures;
        paymentFailureWindow = paymentFailureWindow == null ? Duration.ofHours(1) : paymentFailureWindow;
        newAccountAge = newAccountAge == null ? Duration.ofHours(24) : newAccountAge;
        newAccountAmount = newAccountAmount == null ? new BigDecimal("10000000") : newAccountAmount;
        bulkQuantity = bulkQuantity == null ? 30 : bulkQuantity;
        sharedAddressUsers = sharedAddressUsers == null ? 2 : sharedAddressUsers;
        sharedAddressWindow = sharedAddressWindow == null ? Duration.ofDays(7) : sharedAddressWindow;
        maxEventAge = maxEventAge == null ? Duration.ofMinutes(10) : maxEventAge;
    }

    public static FraudProperties defaults() {
        return new FraudProperties(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }
}
