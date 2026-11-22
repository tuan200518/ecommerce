package com.shop.analytics.streams;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.analytics.AnalyticsProperties;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.config.StreamsBuilderFactoryBeanConfigurer;

@Configuration
@EnableKafkaStreams
@ConditionalOnProperty(prefix = "shop.analytics.streams", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StreamsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StreamsConfiguration.class);

    @Bean
    public AnalyticsTopology analyticsTopology(StreamsBuilder builder, ObjectMapper mapper, AnalyticsProperties props) {
        AnalyticsTopology topology = new AnalyticsTopology(mapper, props.orderSpikeThresholdPerMinute(),
                props.paymentFailureThresholdPerMinute());
        topology.build(builder);
        return topology;
    }

    /** Thread xử lý gặp lỗi không lường trước → thay thread mới thay vì dừng cả ứng dụng. */
    @Bean
    public StreamsBuilderFactoryBeanConfigurer streamsExceptionHandler() {
        return factoryBean -> factoryBean.setStreamsUncaughtExceptionHandler(ex -> {
            log.error("Kafka Streams thread lỗi, khởi tạo lại thread", ex);
            return StreamThreadExceptionResponse.REPLACE_THREAD;
        });
    }
}
