package com.shop.gateway.ratelimit;

import org.springframework.http.HttpMethod;

/** Chọn "xô" giới hạn cho một request. Quy tắc đầu tiên khớp được áp dụng; không khớp → xô mặc định. */
public final class RateLimitPolicy {

    /**
     * @param name    tên xô (dùng trong khóa Redis và nhãn metric)
     * @param perUser true → đếm theo người dùng nếu đã đăng nhập (ngược lại theo IP); false → luôn theo IP
     */
    public record Rule(String name, int limit, boolean perUser) {
    }

    private final RateLimitProperties props;

    public RateLimitPolicy(RateLimitProperties props) {
        this.props = props;
    }

    public Rule match(HttpMethod method, String path) {
        if (HttpMethod.POST.equals(method)) {
            switch (path) {
                case "/api/auth/login" -> {
                    return new Rule("login", props.loginPerMinute(), false);
                }
                case "/api/auth/register" -> {
                    return new Rule("register", props.registerPerMinute(), false);
                }
                case "/api/orders" -> {
                    return new Rule("orders", props.ordersPerMinute(), true);
                }
                case "/api/promotions/quote" -> {
                    return new Rule("promotion-quote", props.promotionQuotePerMinute(), true);
                }
                default -> {
                }
            }
        }
        return new Rule("default", props.defaultPerMinute(), true);
    }
}
