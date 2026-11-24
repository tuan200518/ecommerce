package com.shop.gateway.ratelimit;

import reactor.core.publisher.Mono;

import java.time.Duration;

/** Bộ đếm theo khóa. Tách interface để test không cần Redis. */
public interface RateLimiter {

    /** Tăng bộ đếm của khóa và trả về giá trị mới; khóa tự hết hạn sau {@code ttl}. */
    Mono<Long> increment(String key, Duration ttl);
}
