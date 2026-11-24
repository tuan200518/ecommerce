package com.shop.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Giới hạn số request mỗi phút (cửa sổ cố định 60 giây).
 *
 * @param enabled                 false → tắt hoàn toàn (vd. khi chạy kiểm thử tải k6 với hàng nghìn request/giây)
 * @param loginPerMinute          POST /api/auth/login theo IP – chống dò mật khẩu hàng loạt
 *                                (khóa từng tài khoản sau nhiều lần sai do auth-service đảm nhiệm)
 * @param registerPerMinute       POST /api/auth/register theo IP – chống tạo tài khoản rác
 * @param ordersPerMinute         POST /api/orders theo người dùng – chống bot gom hàng flash sale
 * @param promotionQuotePerMinute kiểm tra mã giảm giá theo người dùng – chống dò mã voucher
 * @param defaultPerMinute        mọi API khác theo người dùng (đã đăng nhập) hoặc theo IP
 */
@ConfigurationProperties(prefix = "shop.rate-limit")
public record RateLimitProperties(Boolean enabled, Integer loginPerMinute, Integer registerPerMinute,
                                  Integer ordersPerMinute, Integer promotionQuotePerMinute,
                                  Integer defaultPerMinute) {

    public RateLimitProperties {
        enabled = enabled == null || enabled;
        loginPerMinute = loginPerMinute == null ? 300 : loginPerMinute;
        registerPerMinute = registerPerMinute == null ? 100 : registerPerMinute;
        ordersPerMinute = ordersPerMinute == null ? 20 : ordersPerMinute;
        promotionQuotePerMinute = promotionQuotePerMinute == null ? 60 : promotionQuotePerMinute;
        defaultPerMinute = defaultPerMinute == null ? 3000 : defaultPerMinute;
    }

    public static RateLimitProperties defaults() {
        return new RateLimitProperties(null, null, null, null, null, null);
    }
}
