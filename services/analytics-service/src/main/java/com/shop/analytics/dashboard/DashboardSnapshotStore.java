package com.shop.analytics.dashboard;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.analytics.dashboard.DashboardState.Persisted;
import com.shop.common.event.Topics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Lưu trạng thái Dashboard (kèm offset đã đọc của topic analytics-events) xuống bảng {@code dashboard_snapshot}
 * để khởi động lại không phải đọc lại toàn bộ topic – topic này lớn dần theo thời gian nên trước đây
 * Dashboard mất vài phút mới có số liệu mới nhất.
 * <p>
 * Snapshot và offset được chụp cùng lúc (cùng khóa của {@link DashboardState}) nên luôn khớp nhau; message đọc lại
 * sau snapshot được áp dụng idempotent. Topic bị tạo lại (id khác) → snapshot bị bỏ, dựng lại từ đầu.
 */
@Component
public class DashboardSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(DashboardSnapshotStore.class);
    static final String ID = "dashboard";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DashboardState state;
    private final KafkaAdmin kafkaAdmin;
    private final boolean enabled;
    private volatile long savedVersion = -1;

    public DashboardSnapshotStore(JdbcTemplate jdbc, ObjectMapper mapper, DashboardState state, KafkaAdmin kafkaAdmin,
                                  @Value("${shop.analytics.snapshot.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.state = state;
        this.kafkaAdmin = kafkaAdmin;
        this.enabled = enabled;
    }

    /** Chạy trước khi listener container khởi động (bean init < SmartLifecycle start). */
    @PostConstruct
    public void load() {
        if (!enabled) {
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT payload, saved_at FROM dashboard_snapshot WHERE id = ?", ID);
            if (rows.isEmpty()) {
                log.info("Chưa có snapshot Dashboard – sẽ dựng lại từ đầu topic {}", Topics.ANALYTICS_EVENTS);
                return;
            }
            Persisted p = mapper.readValue(String.valueOf(rows.get(0).get("payload")), Persisted.class);
            if (p.format() != DashboardState.FORMAT) {
                log.info("Snapshot Dashboard định dạng cũ ({}) – bỏ qua, dựng lại từ đầu topic", p.format());
                return;
            }
            state.restore(p);
            savedVersion = state.version();
            log.info("Đã khôi phục Dashboard từ snapshot lúc {} – đọc tiếp {} từ offset {}",
                    rows.get(0).get("saved_at"), Topics.ANALYTICS_EVENTS, p.offsets());
        } catch (Exception e) {
            // Snapshot hỏng không được làm hỏng Dashboard: dựng lại từ đầu như trước
            state.reset();
            log.warn("Không đọc được snapshot Dashboard ({}) – dựng lại từ đầu topic", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${shop.analytics.snapshot.interval-ms:15000}", initialDelay = 15000)
    public void save() {
        if (!enabled) {
            return;
        }
        long version = state.version();
        if (version == savedVersion || state.isEmpty()) {
            return;
        }
        try {
            Persisted p = state.export();
            String json = mapper.writeValueAsString(p);
            Timestamp now = Timestamp.from(Instant.now());
            // UPDATE rồi INSERT: chạy được trên cả PostgreSQL và H2
            int updated = jdbc.update("UPDATE dashboard_snapshot SET payload = ?, saved_at = ? WHERE id = ?", json, now, ID);
            if (updated == 0) {
                jdbc.update("INSERT INTO dashboard_snapshot (id, payload, saved_at) VALUES (?, ?, ?)", ID, json, now);
            }
            savedVersion = version;
            log.debug("Đã lưu snapshot Dashboard ({} byte, offset {})", json.length(), p.offsets());
        } catch (Exception e) {
            log.warn("Không lưu được snapshot Dashboard: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void saveOnShutdown() {
        save();
    }

    /** Id (UUID) của topic analytics-events hiện tại; null nếu không hỏi được broker. */
    public String currentTopicId() {
        Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);
        try (Admin admin = Admin.create(config)) {
            TopicDescription d = admin.describeTopics(List.of(Topics.ANALYTICS_EVENTS)).allTopicNames()
                    .get(5, TimeUnit.SECONDS).get(Topics.ANALYTICS_EVENTS);
            return d == null || d.topicId() == null ? null : d.topicId().toString();
        } catch (Exception e) {
            log.warn("Không xác định được id topic {}: {}", Topics.ANALYTICS_EVENTS, e.getMessage());
            return null;
        }
    }
}
