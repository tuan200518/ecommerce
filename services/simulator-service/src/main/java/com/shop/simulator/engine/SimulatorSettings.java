package com.shop.simulator.engine;

import com.shop.simulator.SimulatorProperties;

/** Tham số mô phỏng, có thể đổi lúc đang chạy từ trang quản trị. */
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
        boolean autoFulfill) {

    public static SimulatorSettings from(SimulatorProperties p) {
        return new SimulatorSettings(p.sessionsPerMinute(), p.maxConcurrentSessions(), p.browseOnlyRate(),
                p.cartAbandonRate(), p.paymentFailureRate(), p.cancelRate(), p.newCustomerRate(), p.wave(),
                p.autoRestock(), p.autoFulfill()).validated();
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
                wave, autoRestock, autoFulfill);
    }

    private static double clamp(double v, double min, double max) {
        return Double.isNaN(v) ? min : Math.max(min, Math.min(max, v));
    }
}
