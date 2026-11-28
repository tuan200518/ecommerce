package com.shop.analytics.dashboard;

import com.shop.analytics.model.AnalyticsModels.Alert;
import com.shop.analytics.model.AnalyticsModels.MetricUpdate;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.support.kafka.EventReader;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.AbstractConsumerSeekAware;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Đọc kết quả của Kafka Streams (analytics-events) → cập nhật trạng thái Dashboard + lưu bảng tổng hợp.
 * <p>
 * Khi được gán partition, consumer đọc tiếp từ offset đã có trong trạng thái Dashboard (khôi phục từ snapshot –
 * {@link DashboardSnapshotStore}); partition chưa có snapshot thì tua về đầu topic để dựng lại đầy đủ.
 * Việc áp dụng là idempotent (giá trị chỉ số là "mới nhất", cảnh báo khử trùng theo id, ghi DB là upsert)
 * nên đọc lại phần chồng lấn không gây sai lệch.
 */
@Component
public class AnalyticsEventsConsumer extends AbstractConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsEventsConsumer.class);

    private final EventReader reader;
    private final DashboardState state;
    private final AnalyticsRepository repository;
    private static final long RECENT_ALERT_MS = 10 * 60_000L;

    private final DashboardBroadcaster broadcaster;
    private final MeterRegistry meters;
    private final DashboardSnapshotStore snapshots;

    public AnalyticsEventsConsumer(EventReader reader, DashboardState state, AnalyticsRepository repository,
                                   DashboardBroadcaster broadcaster, MeterRegistry meters,
                                   DashboardSnapshotStore snapshots) {
        this.meters = meters;
        this.snapshots = snapshots;
        this.reader = reader;
        this.state = state;
        this.repository = repository;
        this.broadcaster = broadcaster;
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        super.onPartitionsAssigned(assignments, callback);
        Map<Integer, Long> resume = state.resumePositions(snapshots.currentTopicId());
        List<TopicPartition> fromStart = new ArrayList<>();
        for (TopicPartition tp : assignments.keySet()) {
            Long next = resume.get(tp.partition());
            if (next == null) {
                fromStart.add(tp);
            } else {
                callback.seek(tp.topic(), tp.partition(), next);
            }
        }
        if (!fromStart.isEmpty()) {
            callback.seekToBeginning(fromStart);
        }
        log.info("Dashboard: {} partition đọc tiếp từ snapshot {}, {} partition dựng lại từ đầu topic {}",
                assignments.size() - fromStart.size(), resume, fromStart.size(), Topics.ANALYTICS_EVENTS);
    }

    @KafkaListener(id = "analytics-dashboard", topics = Topics.ANALYTICS_EVENTS, concurrency = "1")
    public void onAnalyticsEvent(ConsumerRecord<String, String> record) {
        apply(reader.read(record.value()));
        state.markApplied(record.partition(), record.offset());
    }

    private void apply(EventEnvelope env) {
        if (env.is(EventTypes.METRIC_UPDATED)) {
            MetricUpdate m = reader.payload(env, MetricUpdate.class);
            state.apply(m);
            safely(() -> repository.save(m));
        } else if (env.is(EventTypes.ALERT_RAISED)) {
            Alert a = reader.payload(env, Alert.class);
            if (state.apply(a)) {
                broadcaster.alert(a);
                // Metric cho Prometheus → Alertmanager chuyển cảnh báo nghiệp vụ ra email/chat.
                // Chỉ đếm cảnh báo mới: khi dựng lại Dashboard, consumer có thể đọc lại message cũ (không được đếm lại).
                if (System.currentTimeMillis() - a.at() <= RECENT_ALERT_MS) {
                    meters.counter("shop.analytics.alerts", "type", a.type(), "severity", a.severity()).increment();
                }
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
