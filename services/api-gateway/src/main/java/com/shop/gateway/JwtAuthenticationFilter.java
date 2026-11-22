package com.shop.gateway;

import com.shop.common.security.AuthHeaders;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Xác thực JWT cho mọi request đi qua Gateway.
 * <ol>
 *   <li>Luôn xóa các header X-User-* do client gửi lên (chống giả mạo danh tính).</li>
 *   <li>Đọc token từ header Authorization hoặc query param access_token (EventSource/SSE không gửi được header).</li>
 *   <li>Token hợp lệ → gắn X-User-Id / X-User-Email / X-User-Roles cho service phía sau.</li>
 *   <li>Đường dẫn cần đăng nhập mà không có token hợp lệ → 401; thiếu vai trò → 403.</li>
 * </ol>
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwt;

    public JwtAuthenticationFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        boolean isPublic = AccessPolicy.isPublic(request.getMethod(), path);

        AuthenticatedUser user = null;
        String token = extractToken(request);
        if (token != null) {
            try {
                user = jwt.verify(token);
            } catch (Exception e) {
                log.debug("Token không hợp lệ cho {}: {}", path, e.getMessage());
                if (!isPublic) {
                    return reject(exchange, HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không hợp lệ hoặc đã hết hạn");
                }
            }
        }
        if (user == null && !isPublic) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Yêu cầu đăng nhập");
        }
        if (user != null && !isPublic && !AccessPolicy.isAllowed(user, path)) {
            return reject(exchange, HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập chức năng này");
        }

        final AuthenticatedUser authenticated = user;
        ServerHttpRequest mutated = request.mutate().headers(h -> {
            h.remove(AuthHeaders.USER_ID);
            h.remove(AuthHeaders.USER_EMAIL);
            h.remove(AuthHeaders.USER_ROLES);
            if (authenticated != null) {
                h.set(AuthHeaders.USER_ID, Long.toString(authenticated.userId()));
                h.set(AuthHeaders.USER_EMAIL, authenticated.email() == null ? "" : authenticated.email());
                h.set(AuthHeaders.USER_ROLES, String.join(",", authenticated.roles()));
            }
        }).build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private static String extractToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        String query = request.getQueryParams().getFirst("access_token");
        return query == null || query.isBlank() ? null : query;
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String body = "{\"status\":" + status.value() + ",\"title\":\"" + status.getReasonPhrase()
                + "\",\"detail\":\"" + detail + "\"}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
