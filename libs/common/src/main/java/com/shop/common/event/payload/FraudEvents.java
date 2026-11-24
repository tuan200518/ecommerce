package com.shop.common.event.payload;

import java.math.BigDecimal;
import java.util.List;

/** Payload cho topic fraud-alert-events. */
public final class FraudEvents {

    /** Một luật rủi ro đã kích hoạt: mã luật, số điểm cộng và giải thích dễ hiểu. */
    public record RiskSignal(String rule, int points, String detail) {
    }

    /**
     * Dùng cho RiskAssessed (mọi đơn) và OrderFlagged (đơn cần xác minh).
     *
     * @param level          LOW / MEDIUM / HIGH
     * @param requiresReview true → Order Service chuyển đơn sang UNDER_REVIEW và chờ nhân viên quyết định
     */
    public record RiskAssessed(long orderId, long userId, BigDecimal totalAmount, int score, String level,
                               boolean requiresReview, List<RiskSignal> signals) {
    }

    /** Quyết định xác minh thủ công. */
    public record RiskReviewed(long orderId, long userId, boolean approved, String reviewer, String note) {
    }

    private FraudEvents() {
    }
}
