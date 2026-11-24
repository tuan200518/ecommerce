package com.shop.simulator.engine;

import com.shop.simulator.SimulatorProperties;

/**
 * Tham số mô phỏng, có thể đổi lúc đang chạy từ trang quản trị.
 *
 * @param suspiciousRate tỷ lệ phiên là "khách đáng ngờ" (tài khoản mới, đơn rất lớn) → Fraud Service gắn cờ
 * @param reviewRate     tỷ lệ phiên khách quay lại đánh giá sản phẩm đã nhận
 * @param autoRiskReview nhân viên xác minh ảo tự duyệt/từ chối đơn bị gắn cờ sau ~45 giây
 */
public record SimulatorSettings(
        double sessionsPerMinute,
        int maxConcurrentSessions,
        double browseOnlyRate,
        double cartAbandonRate,
        double paymentFailureRate,
        double cancelRate,
        double newCustomerRate,
        boolean wave,
        boolean autoRestock,
        boolean autoFulfill,
        double suspiciousRate,
        double reviewRate,
        boolean autoRiskReview,
        double voucherRate,
        double flashSaleRate) {

    public static SimulatorSettings from(SimulatorProperties p) {
        return new SimulatorSettings(p.sessionsPerMinute(), p.maxConcurrentSessions(), p.browseOnlyRate(),
                p.cartAbandonRate(), p.paymentFailureRate(), p.cancelRate(), p.newCustomerRate(), p.wave(),
                p.autoRestock(), p.autoFulfill(), p.suspiciousRate(), p.reviewRate(), p.autoRiskReview(),
                p.voucherRate(), p.flashSaleRate()).validated();
    }

    /** Ép các giá trị vào khoảng hợp lệ để cấu hình sai không làm treo/hỏng mô phỏng. */
    public SimulatorSettings validated() {
        return new SimulatorSettings(
                clamp(sessionsPerMinute, 1, 600),
                (int) clamp(maxConcurrentSessions, 1, 200),
                clamp(browseOnlyRate, 0, 1),
                clamp(cartAbandonRate, 0, 1),
                clamp(paymentFailureRate, 0, 1),
                clamp(cancelRate, 0, 1),
                clamp(newCustomerRate, 0, 1),
                wave, autoRestock, autoFulfill,
                clamp(suspiciousRate, 0, 1),
                clamp(reviewRate, 0, 1),
                autoRiskReview,
                clamp(voucherRate, 0, 1),
                clamp(flashSaleRate, 0, 1));
    }

    private static double clamp(double v, double min, double max) {
        return Double.isNaN(v) ? min : Math.max(min, Math.min(max, v));
    }
}
