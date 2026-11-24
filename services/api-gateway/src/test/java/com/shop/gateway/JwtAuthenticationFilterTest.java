package com.shop.gateway;

import com.shop.common.event.Correlation;
import com.shop.common.security.AuthHeaders;
import com.shop.common.security.JwtService;
import com.shop.common.security.Roles;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationFilterTest {

    private final JwtService jwt = new JwtService("test-secret-test-secret-test-secret-123456", Duration.ofMinutes(5));
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwt);

    private record Result(ServerWebExchange forwarded, HttpStatus status) {
    }

    private Result run(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            forwarded.set(ex);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return new Result(forwarded.get(), (HttpStatus) exchange.getResponse().getStatusCode());
    }

    @Test
    void publicCatalogue_isAccessibleWithoutToken() {
        Result r = run(MockServerHttpRequest.get("/api/products?q=phone").build());
        assertThat(r.forwarded()).isNotNull();
    }

    @Test
    void protectedPath_withoutToken_is401() {
        Result r = run(MockServerHttpRequest.post("/api/orders").build());
        assertThat(r.forwarded()).isNull();
        assertThat(r.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validToken_forwardsIdentityHeaders_andStripsSpoofedOnes() {
        String token = jwt.issue(7, "c@shop.local", Set.of(Roles.CUSTOMER));
        Result r = run(MockServerHttpRequest.get("/api/orders/my")
                .header("Authorization", "Bearer " + token)
                .header(AuthHeaders.USER_ID, "1")
                .header(AuthHeaders.USER_ROLES, Roles.ADMIN)
                .header(AuthHeaders.CLIENT_IP, "6.6.6.6")
                .build());
        var headers = r.forwarded().getRequest().getHeaders();
        assertThat(headers.getFirst(AuthHeaders.USER_ID)).isEqualTo("7");
        assertThat(headers.getFirst(AuthHeaders.USER_ROLES)).isEqualTo(Roles.CUSTOMER);
        // IP client do Gateway tự xác định, không lấy theo header client gửi
        assertThat(headers.getFirst(AuthHeaders.CLIENT_IP)).isNotEqualTo("6.6.6.6");
    }

    @Test
    void spoofedHeaders_onPublicPath_areRemoved() {
        Result r = run(MockServerHttpRequest.get("/api/products").header(AuthHeaders.USER_ROLES, Roles.ADMIN)
                .header(AuthHeaders.USER_ID, "1").build());
        assertThat(r.forwarded().getRequest().getHeaders().containsKey(AuthHeaders.USER_ID)).isFalse();
    }

    @Test
    void customer_cannotOpenAnalytics_is403() {
        String token = jwt.issue(7, "c@shop.local", Set.of(Roles.CUSTOMER));
        Result r = run(MockServerHttpRequest.get("/api/analytics/stream").queryParam("access_token", token).build());
        assertThat(r.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void analyst_canOpenAnalyticsStream_withQueryToken() {
        String token = jwt.issue(9, "a@shop.local", Set.of(Roles.ANALYST));
        Result r = run(MockServerHttpRequest.get("/api/analytics/stream").queryParam("access_token", token).build());
        assertThat(r.forwarded()).isNotNull();
    }

    @Test
    void tamperedToken_is401() {
        String token = jwt.issue(7, "c@shop.local", Set.of(Roles.CUSTOMER)) + "x";
        Result r = run(MockServerHttpRequest.get("/api/cart").header("Authorization", "Bearer " + token).build());
        assertThat(r.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void newServices_followRolePolicy() {
        String customer = jwt.issue(7, "c@shop.local", Set.of(Roles.CUSTOMER));
        String analyst = jwt.issue(4, "a@shop.local", Set.of(Roles.ANALYST));
        String warehouse = jwt.issue(3, "w@shop.local", Set.of(Roles.WAREHOUSE_MANAGER));

        // Công khai: đánh giá sản phẩm, kiểm tra mã giới thiệu
        assertThat(run(MockServerHttpRequest.get("/api/reviews/products/5/summary").build()).forwarded()).isNotNull();
        assertThat(run(MockServerHttpRequest.get("/api/loyalty/referrals/SHOPABC").build()).forwarded()).isNotNull();
        assertThat(run(MockServerHttpRequest.post("/api/reviews/products/5").build()).status())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // Khách xem vận đơn của mình nhưng không vào được danh sách vận đơn / hồ sơ rủi ro
        assertThat(run(get("/api/shipments/orders/9", customer)).forwarded()).isNotNull();
        assertThat(run(get("/api/shipments", customer)).status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(get("/api/fraud/cases", customer)).status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(get("/api/reviews/admin/reviews", customer)).status()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(run(get("/api/fraud/cases", analyst)).forwarded()).isNotNull();
        assertThat(run(get("/api/shipments", warehouse)).forwarded()).isNotNull();
        assertThat(run(get("/api/fraud/cases", warehouse)).status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void promotionAndAudit_followRolePolicy() {
        String customer = jwt.issue(7, "c@shop.local", Set.of(Roles.CUSTOMER));
        String analyst = jwt.issue(4, "a@shop.local", Set.of(Roles.ANALYST));
        String admin = jwt.issue(1, "admin@shop.local", Set.of(Roles.ADMIN));

        // Công khai: flash sale đang diễn ra, voucher công khai, danh mục phần thưởng
        assertThat(run(MockServerHttpRequest.get("/api/promotions/flash-sales/active").build()).forwarded()).isNotNull();
        assertThat(run(MockServerHttpRequest.get("/api/promotions/vouchers/public").build()).forwarded()).isNotNull();
        assertThat(run(MockServerHttpRequest.get("/api/loyalty/rewards").build()).forwarded()).isNotNull();
        // Báo giá cần đăng nhập (giới hạn theo người dùng, áp dụng voucher cá nhân)
        assertThat(run(MockServerHttpRequest.post("/api/promotions/quote").build()).status())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(run(get("/api/promotions/vouchers/mine", customer)).forwarded()).isNotNull();

        assertThat(run(get("/api/promotions/admin/vouchers", customer)).status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(get("/api/promotions/admin/vouchers", analyst)).forwarded()).isNotNull();

        // Nhật ký kiểm toán: chỉ ADMIN
        assertThat(run(get("/api/audit", analyst)).status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(get("/api/audit/summary", customer)).status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(get("/api/audit", admin)).forwarded()).isNotNull();
    }

    @Test
    void correlationId_isGeneratedOrPropagated() {
        CorrelationIdFilter correlation = new CorrelationIdFilter();
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            forwarded.set(ex);
            return Mono.empty();
        };

        MockServerWebExchange fresh = MockServerWebExchange.from(MockServerHttpRequest.get("/api/products").build());
        correlation.filter(fresh, chain).block();
        String generated = forwarded.get().getRequest().getHeaders().getFirst(Correlation.HTTP_HEADER);
        assertThat(generated).isNotBlank();
        assertThat(fresh.getResponse().getHeaders().getFirst(Correlation.HTTP_HEADER)).isEqualTo(generated);

        MockServerWebExchange given = MockServerWebExchange.from(MockServerHttpRequest.get("/api/products")
                .header(Correlation.HTTP_HEADER, "TRACE-abc123").build());
        correlation.filter(given, chain).block();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(Correlation.HTTP_HEADER)).isEqualTo("TRACE-abc123");
    }

    private static MockServerHttpRequest get(String path, String token) {
        return MockServerHttpRequest.get(path).header("Authorization", "Bearer " + token).build();
    }
}
