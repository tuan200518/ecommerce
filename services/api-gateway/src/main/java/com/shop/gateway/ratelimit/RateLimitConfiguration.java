package com.shop.gateway.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    public RateLimiter shopRedisRateLimiter(ReactiveStringRedisTemplate redis) {
        return new RedisRateLimiter(redis);
    }

    @Bean
    public RateLimitFilter rateLimitFilter(RateLimitProperties props, RateLimiter limiter,
                                           ObjectProvider<MeterRegistry> meters) {
        MeterRegistry registry = meters.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new);
        return new RateLimitFilter(props, limiter, registry, Clock.systemUTC());
    }
}
