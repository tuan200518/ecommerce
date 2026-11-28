package com.shop.gateway;

import com.shop.common.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }

    /** Định tuyến: giữ nguyên đường dẫn /api/... khi chuyển tiếp tới service. */
    @Bean
    RouteLocator routes(RouteLocatorBuilder builder, ServiceUrls urls) {
        return builder.routes()
                .route("auth-service", r -> r.path("/api/auth/**").uri(urls.auth()))
                .route("user-service", r -> r.path("/api/users/**").uri(urls.user()))
                .route("product-service", r -> r.path("/api/products/**", "/api/categories/**").uri(urls.product()))
                .route("cart-service", r -> r.path("/api/cart/**").uri(urls.cart()))
                .route("order-service", r -> r.path("/api/orders/**").uri(urls.order()))
                .route("inventory-service", r -> r.path("/api/inventory/**").uri(urls.inventory()))
                .route("payment-service", r -> r.path("/api/payments/**").uri(urls.payment()))
                .route("notification-service", r -> r.path("/api/notifications/**").uri(urls.notification()))
                .route("analytics-service", r -> r.path("/api/analytics/**").uri(urls.analytics()))
                .route("simulator-service", r -> r.path("/api/simulator", "/api/simulator/**").uri(urls.simulator()))
                .route("fraud-service", r -> r.path("/api/fraud/**").uri(urls.fraud()))
                .route("loyalty-service", r -> r.path("/api/loyalty/**").uri(urls.loyalty()))
                .route("shipping-service", r -> r.path("/api/shipments", "/api/shipments/**").uri(urls.shipping()))
                .route("review-service", r -> r.path("/api/reviews/**").uri(urls.review()))
                .route("promotion-service", r -> r.path("/api/promotions/**").uri(urls.promotion()))
                .route("audit-service", r -> r.path("/api/audit", "/api/audit/**").uri(urls.audit()))
                .route("support-service", r -> r.path("/api/support/**").uri(urls.support()))
                .route("recommendation-service", r -> r.path("/api/recommendations/**").uri(urls.recommendation()))
                .build();
    }

    @Bean
    JwtService jwtService(@Value("${shop.jwt.secret}") String secret) {
        return new JwtService(secret, Duration.ofHours(8));
    }

    @Bean
    CorsWebFilter corsWebFilter(@Value("${shop.cors.allowed-origins}") String origins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return new CorsWebFilter(source);
    }
}
