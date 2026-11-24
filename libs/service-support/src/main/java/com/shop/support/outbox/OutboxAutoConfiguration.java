package com.shop.support.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.support.ShopProperties;
import com.shop.support.audit.AuditTrail;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@AutoConfiguration(after = {JdbcTemplateAutoConfiguration.class, KafkaAutoConfiguration.class})
@ConditionalOnBean(JdbcTemplate.class)
@EnableConfigurationProperties(ShopProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    public SupportSchemaInitializer supportSchemaInitializer(JdbcTemplate jdbc) {
        return new SupportSchemaInitializer(jdbc);
    }

    @Bean
    public OutboxService outboxService(JdbcTemplate jdbc, ObjectMapper mapper,
                                       @Value("${spring.application.name:unknown-service}") String source,
                                       SupportSchemaInitializer schema) {
        return new OutboxService(jdbc, mapper, source);
    }

    @Bean
    public IdempotencyGuard idempotencyGuard(JdbcTemplate jdbc, SupportSchemaInitializer schema) {
        return new IdempotencyGuard(jdbc);
    }

    @Bean
    public AuditTrail auditTrail(OutboxService outbox) {
        return new AuditTrail(outbox);
    }

    /** Gauge shop_outbox_pending: số sự kiện chưa được relay lên Kafka (tăng dần = Kafka/relay có vấn đề). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class OutboxMetricsConfiguration {

        @Bean
        public MeterBinder outboxPendingGauge(JdbcTemplate jdbc, SupportSchemaInitializer schema) {
            return registry -> Gauge.builder("shop.outbox.pending", jdbc, j -> {
                        try {
                            Long n = j.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL",
                                    Long.class);
                            return n == null ? 0 : n;
                        } catch (Exception e) {
                            return Double.NaN;
                        }
                    })
                    .description("Số sự kiện outbox chưa được gửi lên Kafka")
                    .register(registry);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "shop.outbox", name = "relay-enabled", havingValue = "true", matchIfMissing = true)
    static class RelayConfiguration {

        @Bean
        public OutboxRelay outboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
                                       ShopProperties props, SupportSchemaInitializer schema) {
            return new OutboxRelay(jdbc, kafka, props.getOutbox().getBatchSize());
        }
    }
}
