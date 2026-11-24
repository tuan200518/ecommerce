package com.shop.gateway;

import com.shop.common.security.AuthHeaders;
import com.shop.gateway.ratelimit.RateLimitFilter;
import com.shop.gateway.ratelimit.RateLimitProperties;
import com.shop.gateway.ratelimit.RateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    /** Bộ đếm trong bộ nhớ thay cho Redis. */
    static class InMemoryLimiter implements RateLimiter {
        final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();
        boolean failing;

        @Override
        public Mono<Long> increment(String key, Duration ttl) {
            if (failing) {
                return Mono.error(new IllegalStateException("Redis down"));
            }
            return Mono.just(counts.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet());
        }
    }

    private final InMemoryLimiter limiter = new InMemoryLimiter();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    // 10 giây sau đầu phút → Retry-After = 50
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T10:00:10Z"), ZoneOffset.UTC);

    private RateLimitFilter filter(RateLimitProperties props) {
        return new RateLimitFilter(props, limiter, meters, clock);
    }

    private static RateLimitProperties props(boolean enabled, int login, int orders) {
        return new RateLimitProperties(enabled, login, 100, orders, 60, 1000);
    }

    private record Outcome(boolean forwarded, HttpStatus status, HttpHeaders headers) {
    }

    private Outcome run(RateLimitFilter filter, MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicBoolean forwarded = new AtomicBoolean();
        GatewayFilterChain chain = ex -> {
            forwarded.set(true);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return new Outcome(forwarded.get(), (HttpStatus) exchange.getResponse().getStatusCode(),
                exchange.getResponse().getHeaders());
    }

    private static MockServerHttpRequest login(String ip) {
        return MockServerHttpRequest.post("/api/auth/login").header(AuthHeaders.CLIENT_IP, ip).build();
    }

    @Test
    void login_isLimitedPerIp_with429AndRetryAfter() {
        RateLimitFilter f = filter(props(true, 3, 20));
        for (int i = 0; i < 3; i++) {
            assertThat(run(f, login("1.1.1.1")).forwarded()).isTrue();
        }
        Outcome blocked = run(f, login("1.1.1.1"));
        assertThat(blocked.forwarded()).isFalse();
        assertThat(blocked.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("50");
        assertThat(blocked.headers().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(meters.counter("shop.gateway.rate.limited", "rule", "login").count()).isEqualTo(1.0);

        // IP khác có hạn mức riêng
        assertThat(run(f, login("2.2.2.2")).forwarded()).isTrue();
    }

    @Test
    void orders_areLimitedPerUser_notPerIp() {
        RateLimitFilter f = filter(props(true, 300, 2));
        MockServerHttpRequest.BaseBuilder<?> user7 = MockServerHttpRequest.post("/api/orders")
                .header(AuthHeaders.USER_ID, "7").header(AuthHeaders.CLIENT_IP, "10.0.0.1");
        assertThat(run(f, user7.build()).forwarded()).isTrue();
        assertThat(run(f, user7.build()).forwarded()).isTrue();
        assertThat(run(f, user7.build()).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Cùng IP (vd. sau NAT) nhưng người dùng khác → không bị ảnh hưởng
        assertThat(run(f, MockServerHttpRequest.post("/api/orders").header(AuthHeaders.USER_ID, "8")
                .header(AuthHeaders.CLIENT_IP, "10.0.0.1").build()).forwarded()).isTrue();
        // Xem đơn (GET) dùng xô mặc định
        assertThat(run(f, MockServerHttpRequest.get("/api/orders/my").header(AuthHeaders.USER_ID, "7").build())
                .forwarded()).isTrue();
    }

    @Test
    void redisFailure_failsOpen() {
        limiter.failing = true;
        RateLimitFilter f = filter(props(true, 1, 1));
        for (int i = 0; i < 5; i++) {
            assertThat(run(f, login("1.1.1.1")).forwarded()).isTrue();
        }
    }

    @Test
    void disabled_orNonApiPath_isNotCounted() {
        assertThat(run(filter(props(false, 1, 1)), login("1.1.1.1")).forwarded()).isTrue();
        assertThat(run(filter(props(false, 1, 1)), login("1.1.1.1")).forwarded()).isTrue();
        RateLimitFilter f = filter(props(true, 1, 1));
        run(f, MockServerHttpRequest.get("/index.html").build());
        assertThat(limiter.counts).isEmpty();
    }

    @Test
    void clientIp_trustsRealIpHeaderOnlyFromInternalProxy() {
        MockServerHttpRequest viaNginx = MockServerHttpRequest.get("/api/products")
                .remoteAddress(new InetSocketAddress("172.18.0.20", 41000))
                .header(ClientIp.REAL_IP_HEADER, "203.0.113.9").build();
        assertThat(ClientIp.resolve(viaNginx)).isEqualTo("203.0.113.9");

        MockServerHttpRequest spoofed = MockServerHttpRequest.get("/api/products")
                .remoteAddress(new InetSocketAddress("198.51.100.7", 41000))
                .header(ClientIp.REAL_IP_HEADER, "1.2.3.4").build();
        assertThat(ClientIp.resolve(spoofed)).isEqualTo("198.51.100.7");

        MockServerHttpRequest garbage = MockServerHttpRequest.get("/api/products")
                .remoteAddress(new InetSocketAddress("10.0.0.5", 41000))
                .header(ClientIp.REAL_IP_HEADER, "<script>").build();
        assertThat(ClientIp.resolve(garbage)).isEqualTo("10.0.0.5");
    }
}
