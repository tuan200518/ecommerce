package com.shop.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.analytics")
public record AnalyticsProperties(Long orderSpikeThresholdPerMinute,
                                  Long paymentFailureThresholdPerMinute,
                                  Long broadcastIntervalMs,
                                  Long kafkaMonitorIntervalMs) {

    public AnalyticsProperties {
        if (orderSpikeThresholdPerMinute == null) {
            orderSpikeThresholdPerMinute = 30L;
        }
        if (paymentFailureThresholdPerMinute == null) {
            paymentFailureThresholdPerMinute = 5L;
        }
        if (broadcastIntervalMs == null) {
            broadcastIntervalMs = 1000L;
        }
        if (kafkaMonitorIntervalMs == null) {
            kafkaMonitorIntervalMs = 5000L;
        }
    }
}
