package com.shop.support.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Đọc các bản ghi outbox chưa publish (theo thứ tự id) và gửi lên Kafka.
 * Gửi đồng bộ, dừng ở bản ghi lỗi đầu tiên để giữ thứ tự; lần quét sau sẽ thử lại.
 * Đảm bảo at-least-once → phía consumer phải idempotent (xem {@link IdempotencyGuard}).
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.batchSize = batchSize;
    }

    record Pending(long id, String eventId, String topic, String key, String eventType, String payload) {
    }

    @Scheduled(fixedDelayString = "${shop.outbox.poll-interval-ms:250}", initialDelay = 2000)
    public void relay() {
        List<Pending> batch = jdbc.query("""
                        SELECT id, event_id, topic, message_key, event_type, payload
                        FROM outbox_events WHERE published_at IS NULL ORDER BY id LIMIT ?""",
                (rs, i) -> new Pending(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)),
                batchSize);
        for (Pending p : batch) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(p.topic(), p.key(), p.payload());
                record.headers().add(new RecordHeader("eventType", p.eventType().getBytes(StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader("eventId", p.eventId().getBytes(StandardCharsets.UTF_8)));
                kafka.send(record).get(10, TimeUnit.SECONDS);
                jdbc.update("UPDATE outbox_events SET published_at = ?, attempts = attempts + 1 WHERE id = ?",
                        Timestamp.from(Instant.now()), p.id());
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                String msg = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
                jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                        msg.length() > 1000 ? msg.substring(0, 1000) : msg, p.id());
                log.warn("Publish outbox event {} ({}) thất bại, sẽ thử lại: {}", p.eventId(), p.eventType(), msg);
                return;
            }
        }
        if (!batch.isEmpty()) {
            log.debug("Đã publish {} sự kiện từ outbox", batch.size());
        }
    }

    /** Dọn các bản ghi đã publish quá 3 ngày. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void cleanup() {
        int removed = jdbc.update("DELETE FROM outbox_events WHERE published_at IS NOT NULL AND published_at < ?",
                Timestamp.from(Instant.now().minus(3, ChronoUnit.DAYS)));
        if (removed > 0) {
            log.info("Đã dọn {} bản ghi outbox cũ", removed);
        }
    }
}
