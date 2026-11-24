package com.shop.gateway.ratelimit;

import com.shop.common.security.AuthHeaders;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Giới hạn tần suất request (cửa sổ cố định 1 phút) – chạy SAU {@code JwtAuthenticationFilter} để biết người dùng.
 * <ul>
 *   <li>Vượt giới hạn → 429 problem+json kèm {@code Retry-After}.</li>
 *   <li>Mọi response có {@code X-RateLimit-Limit} / {@code X-RateLimit-Remaining}.</li>
 *   <li>Redis lỗi → cho qua (fail-open) và ghi cảnh báo: rate limit là lớp bảo vệ phụ, không được làm sập cửa hàng.</li>
 * </ul>
 */
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final long WINDOW_SECONDS = 60;
    private static final Duration KEY_TTL = Duration.ofSeconds(WINDOW_SECONDS * 2);

    private final RateLimitProperties props;
    private final RateLimitPolicy policy;
    private final RateLimiter limiter;
    private final MeterRegistry meters;
    private final Clock clock;
    private final AtomicLong lastFailureLog = new AtomicLong();

    public RateLimitFilter(RateLimitProperties props, RateLimiter limiter, MeterRegistry meters, Clock clock) {
        this.props = props;
        this.policy = new RateLimitPolicy(props);
        this.limiter = limiter;
        this.meters = meters;
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        if (!props.enabled() || !path.startsWith("/api/")) {
            return chain.filter(exchange);
        }
        RateLimitPolicy.Rule rule = policy.match(request.getMethod(), path);
        String userId = request.getHeaders().getFirst(AuthHeaders.USER_ID); // do JwtAuthenticationFilter gắn, đã tin cậy
        String ip = request.getHeaders().getFirst(AuthHeaders.CLIENT_IP);
        String subject = rule.perUser() && userId != null ? "u:" + userId : "ip:" + (ip == null ? "unknown" : ip);

        long nowSeconds = clock.instant().getEpochSecond();
        long window = nowSeconds / WINDOW_SECONDS;
        long retryAfter = (window + 1) * WINDOW_SECONDS - nowSeconds;
        String key = "rl:" + rule.name() + ":" + subject + ":" + window;

        return limiter.increment(key, KEY_TTL)
                .onErrorResume(e -> {
                    logFailure(e);
                    return Mono.just(0L);
                })
                .defaultIfEmpty(0L)
                .flatMap(count -> {
                    HttpHeaders headers = exchange.getResponse().getHeaders();
                    headers.set("X-RateLimit-Limit", Integer.toString(rule.limit()));
                    headers.set("X-RateLimit-Remaining", Long.toString(Math.max(0, rule.limit() - count)));
                    if (count > rule.limit()) {
                        meters.counter("shop.gateway.rate.limited", "rule", rule.name()).increment();
                        if (count == rule.limit() + 1) {
                            log.warn("Vượt giới hạn {} request/phút ({}) cho {}", rule.limit(), rule.name(), subject);
                        }
                        return reject(exchange.getResponse(), retryAfter);
                    }
                    return chain.filter(exchange);
                });
    }

    private void logFailure(Throwable e) {
        long now = clock.millis();
        long last = lastFailureLog.get();
        if (now - last > 60_000 && lastFailureLog.compareAndSet(last, now)) {
            log.warn("Không kiểm tra được rate limit (Redis lỗi?) – tạm cho qua: {}", e.toString());
        }
    }

    private static Mono<Void> reject(ServerHttpResponse response, long retryAfter) {
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        String body = "{\"status\":429,\"title\":\"Too Many Requests\",\"detail\":\"Bạn thao tác quá nhanh, vui lòng thử lại sau "
                + retryAfter + " giây\"}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    /** Sau JwtAuthenticationFilter (-100) để đọc được X-User-Id / X-Client-Ip đã tin cậy. */
    @Override
    public int getOrder() {
        return -90;
    }
}
