package com.shop.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamUnavailableFilterTest {

    private final UpstreamUnavailableFilter filter = new UpstreamUnavailableFilter();

    @Test
    void connectionRefused_becomes503WithRetryAfter() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/products").build());
        GatewayFilterChain chain = ex -> Mono.error(new IllegalStateException("routing failed",
                new ConnectException("Connection refused: product-service/172.18.0.20:8083")));

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("10");
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("đang khởi động");
    }

    @Test
    void otherErrors_areNotHidden() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/products").build());
        GatewayFilterChain chain = ex -> Mono.error(new IllegalArgumentException("bug"));

        StepVerifier.create(filter.filter(exchange, chain)).expectError(IllegalArgumentException.class).verify();
    }
}
