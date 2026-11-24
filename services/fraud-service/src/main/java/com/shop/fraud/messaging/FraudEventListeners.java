package com.shop.fraud.messaging;

import com.shop.common.event.Topics;
import com.shop.fraud.service.FraudService;
import com.shop.support.kafka.EventReader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Các Kafka consumer của Fraud Service. id của listener cũng là consumer group id. */
@Component
public class FraudEventListeners {

    private final FraudService service;
    private final EventReader reader;

    public FraudEventListeners(FraudService service, EventReader reader) {
        this.service = service;
        this.reader = reader;
    }

    @KafkaListener(id = "fraud-service.order-events", topics = Topics.ORDER_EVENTS)
    public void onOrderEvent(String message) {
        service.handleOrderEvent(reader.read(message));
    }

    @KafkaListener(id = "fraud-service.payment-events", topics = Topics.PAYMENT_EVENTS)
    public void onPaymentEvent(String message) {
        service.handlePaymentEvent(reader.read(message));
    }

    @KafkaListener(id = "fraud-service.user-events", topics = Topics.USER_EVENTS)
    public void onUserEvent(String message) {
        service.handleUserEvent(reader.read(message));
    }
}
