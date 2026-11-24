package com.shop.common.event;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shop.common.event.payload.OrderEvents.OrderCreated;
import com.shop.common.event.payload.OrderEvents.OrderLine;
import com.shop.common.event.payload.UserEvents.UserRegistered;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EventEnvelopeTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @AfterEach
    void clear() {
        Correlation.clear();
    }

    @Test
    void create_withoutContext_startsNewTrace() {
        EventEnvelope env = EventEnvelope.create(mapper, "X", "test", "1", "payload");
        assertThat(env.correlationId()).isEqualTo(env.eventId());
    }

    @Test
    void create_inheritsCorrelationOfCurrentFlow() {
        Correlation.set("trace-abc");
        EventEnvelope first = EventEnvelope.create(mapper, "A", "test", "1", "x");
        EventEnvelope second = EventEnvelope.create(mapper, "B", "test", "1", "y");
        assertThat(first.correlationId()).isEqualTo("trace-abc");
        assertThat(second.correlationId()).isEqualTo("trace-abc");
        assertThat(first.eventId()).isNotEqualTo(second.eventId());
    }

    @Test
    void oldMessageWithoutCorrelationId_isStillReadable() throws Exception {
        String json = """
                {"eventId":"e1","eventType":"OrderCreated","version":1,"occurredAt":"2026-11-19T03:15:22Z",
                 "source":"order-service","aggregateId":"7",
                 "payload":{"orderId":7,"userId":3,"items":[],"totalAmount":100,"paymentMethod":"MOCK_CARD",
                            "createdAt":"2026-11-19T03:15:22Z"}}""";
        EventEnvelope env = mapper.readValue(json, EventEnvelope.class);
        assertThat(env.correlationId()).isNull();
        OrderCreated payload = env.payloadAs(mapper, OrderCreated.class);
        assertThat(payload.orderId()).isEqualTo(7);
        assertThat(payload.shippingAddress()).isNull();
    }

    @Test
    void eventVersionAlias_andRoundTrip() throws Exception {
        String json = """
                {"eventId":"e2","eventType":"UserRegistered","eventVersion":2,"aggregateId":"5",
                 "payload":{"userId":5,"email":"a@b.c","fullName":"A","roles":["CUSTOMER"],"referralCode":"REF1"},
                 "correlationId":"TRACE-1"}""";
        EventEnvelope env = mapper.readValue(json, EventEnvelope.class);
        assertThat(env.version()).isEqualTo(2);
        assertThat(env.correlationId()).isEqualTo("TRACE-1");
        assertThat(env.payloadAs(mapper, UserRegistered.class).referralCode()).isEqualTo("REF1");

        OrderCreated created = new OrderCreated(1, 2, List.of(new OrderLine(3, "P", 1, BigDecimal.TEN)),
                BigDecimal.TEN, "MOCK_CARD", Instant.now(), "12 Lê Lợi", BigDecimal.ZERO);
        EventEnvelope out = EventEnvelope.create(mapper, "OrderCreated", "test", "1", created);
        EventEnvelope back = mapper.readValue(mapper.writeValueAsString(out), EventEnvelope.class);
        OrderCreated read = back.payloadAs(mapper, OrderCreated.class);
        assertThat(read.shippingAddress()).isEqualTo("12 Lê Lợi");
        assertThat(read.totalAmount()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(read.items()).hasSize(1);
        assertThat(back.correlationId()).isEqualTo(out.correlationId());
        assertThat(new UserRegistered(1, "e", "n", Set.of()).referralCode()).isNull();
    }

    @Test
    void sanitize_rejectsUnsafeCharacters() {
        assertThat(Correlation.sanitize("abc\r\nX-Evil: 1")).isEqualTo("abcX-Evil:1");
        assertThat(Correlation.sanitize("x".repeat(100))).hasSize(64);
    }
}
