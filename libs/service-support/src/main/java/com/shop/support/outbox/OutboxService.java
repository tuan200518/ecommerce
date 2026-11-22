package com.shop.support.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.event.EventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Transactional Outbox: thay vì gửi Kafka trực tiếp, sự kiện được ghi vào bảng outbox_events
 * trong CÙNG transaction với dữ liệu nghiệp vụ. {@link OutboxRelay} sẽ đọc và publish sau đó.
 * Nhờ vậy không xảy ra trường hợp DB commit nhưng sự kiện bị mất (hoặc ngược lại).
 */
public class OutboxService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String source;

    public OutboxService(JdbcTemplate jdbc, ObjectMapper mapper, String source) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.source = source;
    }

    /**
     * Ghi sự kiện vào outbox. Phải được gọi bên trong transaction của nghiệp vụ.
     *
     * @param aggregateId dùng làm Kafka key: các sự kiện của cùng một aggregate (vd. cùng orderId)
     *                    vào cùng partition nên giữ đúng thứ tự.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope publish(String topic, String eventType, String aggregateId, Object payload) {
        EventEnvelope envelope = EventEnvelope.create(mapper, eventType, source, aggregateId, payload);
        String json;
        try {
            json = mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không thể serialize sự kiện " + eventType, e);
        }
        jdbc.update("""
                        INSERT INTO outbox_events (event_id, topic, message_key, event_type, payload, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)""",
                envelope.eventId(), topic, aggregateId, eventType, json, Timestamp.from(Instant.now()));
        return envelope;
    }
}
