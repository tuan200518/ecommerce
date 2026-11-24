package com.shop.support.metrics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Giá trị mặc định (độ ưu tiên thấp nhất – application.yml vẫn ghi đè được) cho giám sát bằng Prometheus:
 * <ul>
 *   <li>mọi metric có nhãn {@code application=<spring.application.name>} để Grafana lọc theo service;</li>
 *   <li>histogram cho http.server.requests → tính được p95/p99 trên Grafana.</li>
 * </ul>
 * Endpoint /actuator/prometheus được bật trong application.yml của từng service (cổng nội bộ, không đi qua Gateway).
 */
public class MetricsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource("shopMetricsDefaults", Map.of(
                "management.metrics.tags.application", "${spring.application.name:unknown-service}",
                "management.metrics.distribution.percentiles-histogram.http.server.requests", "true")));
    }
}
