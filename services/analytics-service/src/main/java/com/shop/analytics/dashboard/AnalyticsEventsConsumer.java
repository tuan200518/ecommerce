package com.shop.analytics.dashboard;

import com.shop.analytics.model.AnalyticsModels.Alert;
import com.shop.analytics.model.AnalyticsModels.MetricUpdate;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.support.kafka.EventReader;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.AbstractConsumerSeekAware;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Đọc kết quả của Kafka Streams (analytics-events) → cập nhật trạng thái Dashboard + lưu bảng tổng hợp.
 * <p>
 * Mỗi khi được gán partition, consumer tua về đầu topic để dựng lại đầy đủ trạng thái Dashboard
 * trong bộ nhớ. Việc áp dụng là idempotent (giá trị chỉ số là "mới nhất", cảnh báo khử trùng theo id,
 * ghi DB là upsert) nên đọc lại không gây sai lệch.
 */
@Component
public class AnalyticsEventsConsumer extends AbstractConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsEventsConsumer.class);

    private final EventReader reader;
    private final DashboardState state;
    private final AnalyticsRepository repository;
    private final DashboardBroadcaster broadcaster;

    public AnalyticsEventsConsumer(EventReader reader, DashboardState state, AnalyticsRepository repository,
                                   DashboardBroadcaster broadcaster) {
        this.reader = reader;
        this.state = state;
        this.repository = repository;
        this.broadcaster = broadcaster;
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        super.onPartitionsAssigned(assignments, callback);
        callback.seekToBeginning(assignments.keySet());
        log.info("Dựng lại Dashboard từ đầu topic {} cho {} partition", Topics.ANALYTICS_EVENTS, assignments.size());
    }

    @KafkaListener(id = "analytics-dashboard", topics = Topics.ANALYTICS_EVENTS, concurrency = "1")
    public void onAnalyticsEvent(String message) {
        EventEnvelope env = reader.read(message);
        if (env.is(EventTypes.METRIC_UPDATED)) {
            MetricUpdate m = reader.payload(env, MetricUpdate.class);
            state.apply(m);
            safely(() -> repository.save(m));
        } else if (env.is(EventTypes.ALERT_RAISED)) {
            Alert a = reader.payload(env, Alert.class);
            if (state.apply(a)) {
                broadcaster.alert(a);
            }
            safely(() -> repository.save(a));
        }
    }

    /** Lỗi ghi DB không được làm dừng việc cập nhật Dashboard realtime. */
    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.warn("Không lưu được dữ liệu tổng hợp: {}", e.getMessage());
        }
    }
}
