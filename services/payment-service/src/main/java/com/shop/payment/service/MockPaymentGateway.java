package com.shop.payment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cổng thanh toán mô phỏng.
 * <ul>
 *   <li>MOCK_CARD / MOCK_WALLET: thành công, trừ một tỷ lệ ngẫu nhiên bị từ chối (cấu hình failure-rate).</li>
 *   <li>MOCK_FAIL: luôn thất bại — dùng để kiểm thử luồng bù (TC03).</li>
 * </ul>
 */
@Component
public class MockPaymentGateway {

    public record GatewayResult(boolean success, String reference, String message) {
    }

    private final double failureRate;
    private final long delayMs;

    public MockPaymentGateway(@Value("${shop.payment.failure-rate:0.1}") double failureRate,
                              @Value("${shop.payment.processing-delay-ms:50}") long delayMs) {
        this.failureRate = failureRate;
        this.delayMs = delayMs;
    }

    public GatewayResult charge(String method, BigDecimal amount) {
        simulateLatency();
        String ref = "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        if ("MOCK_FAIL".equals(method)) {
            return new GatewayResult(false, ref, "Thẻ bị từ chối (mô phỏng)");
        }
        if (amount.signum() <= 0) {
            return new GatewayResult(false, ref, "Số tiền không hợp lệ");
        }
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            return new GatewayResult(false, ref, "Ngân hàng từ chối giao dịch (mô phỏng ngẫu nhiên)");
        }
        return new GatewayResult(true, ref, "Giao dịch thành công");
    }

    public GatewayResult refund(BigDecimal amount) {
        simulateLatency();
        return new GatewayResult(true, "RFD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "Hoàn tiền thành công");
    }

    private void simulateLatency() {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
