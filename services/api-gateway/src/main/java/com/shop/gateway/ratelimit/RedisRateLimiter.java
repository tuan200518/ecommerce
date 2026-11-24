package com.shop.gateway.ratelimit;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Bộ đếm dùng chung qua Redis → giới hạn đúng cả khi chạy nhiều instance Gateway.
 * Khóa đã chứa chỉ số cửa sổ thời gian nên INCR + EXPIRE không cần nguyên tử tuyệt đối:
 * nếu EXPIRE lỡ thất bại, khóa cũ chỉ tốn chút bộ nhớ chứ không ảnh hưởng cửa sổ sau.
 */
public class RedisRateLimiter implements RateLimiter {

    private final ReactiveStringRedisTemplate redis;

    public RedisRateLimiter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Long> increment(String key, Duration ttl) {
        return redis.opsForValue().increment(key)
                .flatMap(count -> count == 1
                        ? redis.expire(key, ttl).thenReturn(count)
                        : Mono.just(count));
    }
}
