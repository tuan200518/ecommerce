package com.shop.common.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Định dạng chuẩn cho mọi sự kiện Kafka trong hệ thống.
 * <p>
 * Mỗi sự kiện có: eventId (dùng cho idempotency), eventType, version (phiên bản schema),
 * occurredAt, source (service phát), aggregateId (dùng làm Kafka key để đảm bảo thứ tự theo aggregate)
 * và payload.
 */
public record EventEnvelope(
        String eventId,
        String eventType,
        int version,
        Instant occurredAt,
        String source,
        String aggregateId,
        JsonNode payload
) {

    public static final int CURRENT_VERSION = 1;

    public static EventEnvelope create(ObjectMapper mapper, String eventType, String source,
                                       String aggregateId, Object payload) {
        return new EventEnvelope(
                UUID.randomUUID().toString(),
                eventType,
                CURRENT_VERSION,
                Instant.now(),
                source,
                aggregateId,
                mapper.valueToTree(payload));
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
