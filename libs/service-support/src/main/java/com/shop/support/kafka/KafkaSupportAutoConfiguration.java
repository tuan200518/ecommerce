package com.shop.support.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.event.Topics;
import com.shop.support.ShopProperties;
import com.shop.support.correlation.CorrelationRecordInterceptor;
import org.springframework.kafka.listener.RecordInterceptor;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.apache.kafka.common.TopicPartition;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cấu hình Kafka dùng chung:
 * <ul>
 *   <li>Tạo các topic nghiệp vụ + topic DLT tương ứng.</li>
 *   <li>Retry với exponential backoff; quá số lần retry → Dead Letter Topic (topic + "-dlt").</li>
 * </ul>
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@EnableConfigurationProperties(ShopProperties.class)
public class KafkaSupportAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaSupportAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public EventReader eventReader(ObjectMapper mapper) {
        return new EventReader(mapper);
    }

    /** Gắn correlationId từ header Kafka vào thread trong lúc listener xử lý message. */
    @Bean
    @ConditionalOnMissingBean(RecordInterceptor.class)
    public RecordInterceptor<Object, Object> correlationRecordInterceptor() {
        return new CorrelationRecordInterceptor();
    }

    /**
     * Khi Kafka ngừng hoạt động, send() không chờ metadata quá 10 giây (mặc định 60 giây)
     * → Outbox relay nhanh chóng thử lại ở lần quét sau thay vì chiếm thread scheduler.
     */
    @Bean
    public DefaultKafkaProducerFactoryCustomizer shopProducerCustomizer() {
        return factory -> factory.updateConfigs(Map.of(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000));
    }

    @Bean
    @ConditionalOnProperty(prefix = "shop.kafka", name = "create-topics", havingValue = "true", matchIfMissing = true)
    public KafkaAdmin.NewTopics shopTopics(ShopProperties props) {
        List<NewTopic> topics = new ArrayList<>();
        for (String name : Topics.ALL) {
            topics.add(TopicBuilder.name(name)
                    .partitions(props.getKafka().getPartitions())
                    .replicas(props.getKafka().getReplicas())
                    .build());
            topics.add(TopicBuilder.name(name + Topics.DLT_SUFFIX)
                    .partitions(1)
                    .replicas(props.getKafka().getReplicas())
                    .build());
        }
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template, ShopProperties props,
                                                ObjectProvider<MeterRegistry> meters) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> {
                    // Metric shop_kafka_dlt_total{topic}: Prometheus cảnh báo khi có message rơi vào DLT
                    meters.ifAvailable(registry -> registry.counter("shop.kafka.dlt", "topic", record.topic()).increment());
                    log.error("Đưa message vào DLT sau khi retry thất bại: topic={} partition={} offset={} key={} lỗi={}",
                            record.topic(), record.partition(), record.offset(), record.key(), ex.getMessage());
                    return new TopicPartition(record.topic() + Topics.DLT_SUFFIX, -1);
                });
        ShopProperties.Kafka k = props.getKafka();
        ExponentialBackOff backOff = new ExponentialBackOff(k.getRetryInitialIntervalMs(), k.getRetryMultiplier());
        backOff.setMaxAttempts(k.getMaxRetries());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(MalformedEventException.class, DeserializationException.class);
        handler.setRetryListeners((record, ex, attempt) ->
                log.warn("Retry lần {} cho message topic={} offset={}: {}",
                        attempt, record.topic(), record.offset(), ex.getMessage()));
        return handler;
    }
}
