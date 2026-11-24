package com.shop.gateway;

import com.shop.common.event.Correlation;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Điểm bắt đầu của truy vết: mỗi request qua Gateway có một {@value Correlation#HTTP_HEADER}
 * (giữ giá trị client gửi nếu hợp lệ, nếu không thì tạo mới), được chuyển tiếp xuống service phía sau
 * và trả lại cho client trong response. Service ghi giá trị này vào mọi sự kiện Kafka phát ra.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(Correlation.HTTP_HEADER);
        String id = incoming == null || incoming.isBlank() ? Correlation.newId() : Correlation.sanitize(incoming);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(Correlation.HTTP_HEADER, id))
                .build();
        exchange.getResponse().getHeaders().set(Correlation.HTTP_HEADER, id);
        return chain.filter(exchange.mutate().request(request).build());
    }

    /** Chạy trước JwtAuthenticationFilter (-100) để cả response 401/403 cũng có correlationId. */
    @Override
    public int getOrder() {
        return -200;
    }
}
