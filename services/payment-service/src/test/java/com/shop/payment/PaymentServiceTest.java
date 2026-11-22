package com.shop.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.payload.OrderEvents.PaymentRequested;
import com.shop.payment.domain.Payment;
import com.shop.payment.domain.PaymentRepository;
import com.shop.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.listener.auto-startup=false",
        "shop.outbox.relay-enabled=false",
        "shop.kafka.create-topics=false",
        "shop.payment.failure-rate=0",
        "shop.payment.processing-delay-ms=0"
})
class PaymentServiceTest {

    @Autowired
    PaymentService service;
    @Autowired
    PaymentRepository payments;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    private EventEnvelope request(long orderId, String method) {
        return EventEnvelope.create(mapper, EventTypes.PAYMENT_REQUESTED, "test", String.valueOf(orderId),
                new PaymentRequested(orderId, 5, new BigDecimal("990000"), method));
    }

    private int outboxCount(String type, long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = ? AND message_key = ?",
                Integer.class, type, String.valueOf(orderId));
    }

    @Test
    void successfulPayment_isProcessedOnce_evenIfEventIsRedelivered() {
        EventEnvelope env = request(100, "MOCK_CARD");
        PaymentRequested req = env.payloadAs(mapper, PaymentRequested.class);
        service.process(env, req);
        service.process(env, req); // TC09: giao lại cùng sự kiện
        service.process(request(100, "MOCK_CARD"), req); // sự kiện khác nhưng cùng đơn → ràng buộc nghiệp vụ

        assertThat(payments.findByOrderId(100).orElseThrow().getStatus()).isEqualTo(Payment.Status.SUCCEEDED);
        assertThat(payments.count()).isGreaterThanOrEqualTo(1);
        assertThat(outboxCount(EventTypes.PAYMENT_COMPLETED, 100)).isEqualTo(1);
    }

    @Test
    void failingMethod_publishesPaymentFailed() {
        EventEnvelope env = request(200, "MOCK_FAIL");
        service.process(env, env.payloadAs(mapper, PaymentRequested.class));
        Payment p = payments.findByOrderId(200).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.FAILED);
        assertThat(outboxCount(EventTypes.PAYMENT_FAILED, 200)).isEqualTo(1);
    }

    @Test
    void cancelAfterPayment_refunds() {
        EventEnvelope env = request(300, "MOCK_CARD");
        service.process(env, env.payloadAs(mapper, PaymentRequested.class));
        EventEnvelope cancelled = EventEnvelope.create(mapper, EventTypes.ORDER_CANCELLED, "test", "300", "x");
        service.refundIfPaid(cancelled, 300, "Khách hủy");
        service.refundIfPaid(cancelled, 300, "Khách hủy");
        assertThat(payments.findByOrderId(300).orElseThrow().getStatus()).isEqualTo(Payment.Status.REFUNDED);
        assertThat(outboxCount(EventTypes.PAYMENT_REFUNDED, 300)).isEqualTo(1);
    }
}
