package com.shop.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param suspiciousIpEmails  một IP đăng nhập sai vào từ chừng này email khác nhau trong {@code suspiciousIpWindow}
 *                            → cảnh báo nghi ngờ dò mật khẩu hàng loạt (credential stuffing)
 * @param suspiciousIpWindow  cửa sổ thời gian cho quy tắc trên
 * @param alertMaxAge         chỉ cảnh báo cho sự kiện mới (không cảnh báo lại khi đọc lại topic từ đầu)
 */
@ConfigurationProperties(prefix = "shop.audit")
public record AuditProperties(Integer suspiciousIpEmails, Duration suspiciousIpWindow, Duration alertMaxAge) {

    public AuditProperties {
        suspiciousIpEmails = suspiciousIpEmails == null ? 5 : suspiciousIpEmails;
        suspiciousIpWindow = suspiciousIpWindow == null ? Duration.ofMinutes(10) : suspiciousIpWindow;
        alertMaxAge = alertMaxAge == null ? Duration.ofMinutes(10) : alertMaxAge;
    }
}
