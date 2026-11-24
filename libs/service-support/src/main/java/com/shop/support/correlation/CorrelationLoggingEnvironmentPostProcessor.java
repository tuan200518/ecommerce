package com.shop.support.correlation;

import com.shop.common.event.Correlation;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Mặc định in correlationId trong mọi dòng log của các service dùng service-support
 * (vd. {@code [3f2a...] c.s.o.service.OrderService : Tạo đơn #12}). Có thể ghi đè bằng
 * {@code logging.pattern.correlation} trong application.yml.
 */
public class CorrelationLoggingEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource("shopCorrelationLogging",
                Map.of("logging.pattern.correlation", "[%X{" + Correlation.MDC_KEY + ":-}] ")));
    }
}
