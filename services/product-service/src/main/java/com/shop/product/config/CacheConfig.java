package com.shop.product.config;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.product.service.ProductService;
import com.shop.product.web.ProductDtos.CategoryDto;
import com.shop.product.web.ProductDtos.ProductDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Cache Redis cho chi tiết sản phẩm và danh mục (dữ liệu đọc nhiều, ghi ít).
 * Nếu Redis không khả dụng, request vẫn chạy bình thường (chỉ ghi log) nhờ CacheErrorHandler.
 */
@Configuration
@EnableCaching
@ConditionalOnProperty(prefix = "shop.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory factory, ObjectMapper mapper,
                                     @Value("${shop.cache.ttl:PT10M}") Duration ttl) {
        JavaType categoryList = mapper.getTypeFactory().constructCollectionType(List.class, CategoryDto.class);
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .prefixCacheNameWith("shop:");
        return RedisCacheManager.builder(factory)
                .cacheDefaults(base)
                .withInitialCacheConfigurations(Map.of(
                        ProductService.PRODUCT_CACHE, base.serializeValuesWith(
                                SerializationPair.fromSerializer(new Jackson2JsonRedisSerializer<>(mapper, ProductDto.class))),
                        ProductService.CATEGORY_CACHE, base.serializeValuesWith(
                                SerializationPair.fromSerializer(new Jackson2JsonRedisSerializer<>(mapper, categoryList)))))
                .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("Redis get lỗi ({}:{}): {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("Redis put lỗi ({}:{}): {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("Redis evict lỗi ({}:{}): {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("Redis clear lỗi ({}): {}", cache.getName(), e.getMessage());
            }
        };
    }
}
