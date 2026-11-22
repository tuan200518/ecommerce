package com.shop.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GatewayContextTest {

    @Autowired
    RouteLocator routes;

    @Test
    void allServiceRoutesAreRegistered() {
        assertThat(routes.getRoutes().collectList().block())
                .extracting(r -> r.getId())
                .contains("auth-service", "user-service", "product-service", "cart-service", "order-service",
                        "inventory-service", "payment-service", "notification-service", "analytics-service",
                        "simulator-service");
    }
}
