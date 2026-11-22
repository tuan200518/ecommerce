package com.shop.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "shop.order")
public record OrderProperties(Duration processingTimeout, Integer maxQuantityPerItem) {

    public OrderProperties {
        if (processingTimeout == null) {
            processingTimeout = Duration.ofMinutes(2);
        }
        if (maxQuantityPerItem == null) {
            maxQuantityPerItem = 20;
        }
    }
}
