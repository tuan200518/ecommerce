package com.shop.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

/**
 * Service phía sau chưa chạy / đang khởi động (kết nối bị từ chối, chưa phân giải được tên, quá thời gian kết nối)
 * → trả 503 problem+json kèm Retry-After thay vì 500 mặc định của Spring Cloud Gateway. 500 dễ bị hiểu nhầm là
 * lỗi dữ liệu, trong khi thực tế chỉ cần chờ service khởi động xong (17 JVM cần vài phút khi chạy cùng lúc).
 */
@Component
public class UpstreamUnavailableFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(UpstreamUnavailableFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).onErrorResume(UpstreamUnavailableFilter::isUnavailable, e -> {
            ServerHttpResponse response = exchange.getResponse();
            if (response.isCommitted()) {
                return Mono.error(e);
            }
            log.warn("Service phía sau chưa sẵn sàng cho {} {}: {}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(), e.getMessage());
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, "10");
            String body = "{\"status\":503,\"title\":\"Service Unavailable\",\"detail\":\"Dịch vụ đang khởi động hoặc tạm thời gián đoạn, vui lòng thử lại sau ít giây\"}";
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
        });
    }

    static boolean isUnavailable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException || t instanceof TimeoutException
                    || t.getClass().getSimpleName().equals("ConnectTimeoutException")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** Ngoài cùng (trước CorrelationIdFilter -200) để bắt lỗi từ toàn bộ chuỗi filter, kể cả bước định tuyến. */
    @Override
    public int getOrder() {
        return -300;
    }
}
