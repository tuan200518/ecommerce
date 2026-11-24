package com.shop.common.event;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Định dạng chuẩn cho mọi sự kiện Kafka trong hệ thống.
 * <p>
 * Mỗi sự kiện có: eventId (dùng cho idempotency), eventType, version (phiên bản schema),
 * occurredAt, source (service phát), aggregateId (dùng làm Kafka key để đảm bảo thứ tự theo aggregate),
 * payload và correlationId (truy vết một luồng nghiệp vụ xuyên nhiều service, xem {@link Correlation}).
 * <p>
 * Tương thích ngược: message cũ không có correlationId vẫn đọc được (giá trị null);
 * trường {@code version} cũng chấp nhận tên {@code eventVersion}.
 */
public record EventEnvelope(
        String eventId,
        String eventType,
        @JsonAlias("eventVersion") int version,
        Instant occurredAt,
        String source,
        String aggregateId,
        JsonNode payload,
        String correlationId
) {

    public static final int CURRENT_VERSION = 1;

    /** Constructor cũ (trước khi có correlationId), giữ lại cho mã nguồn hiện có. */
    public EventEnvelope(String eventId, String eventType, int version, Instant occurredAt, String source,
                         String aggregateId, JsonNode payload) {
        this(eventId, eventType, version, occurredAt, source, aggregateId, payload, null);
    }

    /**
     * Tạo sự kiện mới. correlationId lấy từ luồng hiện tại ({@link Correlation#current()});
     * nếu không có thì sự kiện này bắt đầu một luồng truy vết mới (correlationId = eventId).
     */
    public static EventEnvelope create(ObjectMapper mapper, String eventType, String source,
                                       String aggregateId, Object payload) {
        String eventId = UUID.randomUUID().toString();
        String correlationId = Correlation.current();
        return new EventEnvelope(
                eventId,
                eventType,
                CURRENT_VERSION,
                Instant.now(),
                source,
                aggregateId,
                mapper.valueToTree(payload),
                correlationId != null ? correlationId : eventId);
    }

    public <T> T payloadAs(ObjectMapper mapper, Class<T> type) {
        try {
            return mapper.treeToValue(payload, type);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Không thể đọc payload của sự kiện " + eventType + " (" + eventId + ")", e);
        }
    }

    public boolean is(String type) {
        return type.equals(eventType);
    }
}
