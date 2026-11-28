package com.shop.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình khởi tạo; giá trị chạy thực tế nằm trong {@link com.shop.simulator.engine.SimulatorSettings} (đổi được lúc chạy). */
@ConfigurationProperties(prefix = "shop.simulator")
public record SimulatorProperties(
        String gatewayUrl,
        boolean autostart,
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
        double flashSaleRate,
        double supportRate,
        boolean autoSupport,
        double recommendationRate,
        int customerCount,
        String customerPassword,
        String staffEmail,
        String staffPassword,
        String analystEmail,
        String analystPassword,
        String supportEmail,
        String supportPassword) {
}
