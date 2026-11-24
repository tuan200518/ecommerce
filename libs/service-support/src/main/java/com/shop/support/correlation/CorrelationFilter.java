package com.shop.support.correlation;

import com.shop.common.event.Correlation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Mỗi request REST: lấy correlationId từ header (do API Gateway gắn) hoặc tạo mới, gắn vào thread + MDC
 * và trả lại trong response header. Sự kiện ghi vào Outbox trong request này sẽ mang cùng correlationId.
 */
public class CorrelationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(Correlation.HTTP_HEADER);
        String id = incoming == null || incoming.isBlank() ? Correlation.newId() : Correlation.sanitize(incoming);
        Correlation.set(id);
        MDC.put(Correlation.MDC_KEY, id);
        response.setHeader(Correlation.HTTP_HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            Correlation.clear();
            MDC.remove(Correlation.MDC_KEY);
        }
    }
}
