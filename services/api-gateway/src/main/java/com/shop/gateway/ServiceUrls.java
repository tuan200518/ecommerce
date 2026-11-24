package com.shop.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.services")
public record ServiceUrls(String auth, String user, String product, String cart, String order,
                          String inventory, String payment, String notification, String analytics,
                          String simulator, String fraud, String loyalty, String shipping, String review,
                          String promotion, String audit) {
}
