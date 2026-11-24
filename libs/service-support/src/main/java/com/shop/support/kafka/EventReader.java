package com.shop.support.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.event.Correlation;
import com.shop.common.event.EventEnvelope;
import org.slf4j.MDC;

/** Chuyển message JSON từ Kafka thành {@link EventEnvelope} và payload kiểu mạnh. */
public class EventReader {

    private final ObjectMapper mapper;

    public EventReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @throws MalformedEventException nếu message không đúng định dạng: lỗi không retry được,
     *                                 sẽ được đưa thẳng vào DLT.
     */
    public EventEnvelope read(String json) {
        try {
            EventEnvelope env = mapper.readValue(json, EventEnvelope.class);
            if (env.eventId() == null || env.eventType() == null) {
                throw new MalformedEventException("Sự kiện thiếu eventId/eventType");
            }
            if (Correlation.current() == null && env.correlationId() != null) {
                // Message không có header correlationId (vd. phát từ phiên bản cũ) → lấy từ envelope
                Correlation.set(env.correlationId());
                MDC.put(Correlation.MDC_KEY, Correlation.current());
            }
            return env;
        } catch (MalformedEventException e) {
            throw e;
        } catch (Exception e) {
            throw new MalformedEventException("Message không phải EventEnvelope hợp lệ: " + e.getMessage(), e);
        }
    }

    public <T> T payload(EventEnvelope env, Class<T> type) {
        try {
            return mapper.treeToValue(env.payload(), type);
        } catch (Exception e) {
            throw new MalformedEventException("Payload " + env.eventType() + " không hợp lệ: " + e.getMessage(), e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
