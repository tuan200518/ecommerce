package com.shop.support.outbox;

import com.shop.common.event.EventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Chống xử lý trùng sự kiện (idempotent consumer).
 * <p>
 * Gọi {@link #firstTime} ở đầu handler, trong cùng transaction với thay đổi nghiệp vụ:
 * nếu nghiệp vụ lỗi thì dấu "đã xử lý" cũng rollback, sự kiện sẽ được retry đúng cách.
 * Nếu cùng eventId được gửi lại (retry của producer, rebalance, replay...), handler bỏ qua.
 */
public class IdempotencyGuard {

    private final JdbcTemplate jdbc;

    public IdempotencyGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true nếu đây là lần đầu consumer này nhận sự kiện. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(EventEnvelope event, String consumer) {
        int inserted = jdbc.update("""
                        INSERT INTO processed_events (event_id, consumer, event_type, processed_at)
                        VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                event.eventId(), consumer, event.eventType(), Timestamp.from(Instant.now()));
        return inserted == 1;
    }
}
