package com.shop.gateway;

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
                .build());
        var headers = r.forwarded().getRequest().getHeaders();
        assertThat(headers.getFirst(AuthHeaders.USER_ID)).isEqualTo("7");
        assertThat(headers.getFirst(AuthHeaders.USER_ROLES)).isEqualTo(Roles.CUSTOMER);
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
}
