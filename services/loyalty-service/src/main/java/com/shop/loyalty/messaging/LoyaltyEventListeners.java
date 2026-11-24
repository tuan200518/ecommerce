package com.shop.loyalty.messaging;

import com.shop.common.event.Topics;
import com.shop.loyalty.service.LoyaltyService;
import com.shop.support.kafka.EventReader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class LoyaltyEventListeners {

    private final LoyaltyService service;
    private final EventReader reader;

    public LoyaltyEventListeners(LoyaltyService service, EventReader reader) {
        this.service = service;
        this.reader = reader;
    }

    @KafkaListener(id = "loyalty-service.user-events", topics = Topics.USER_EVENTS)
    public void onUserEvent(String message) {
        service.handleUserEvent(reader.read(message));
    }

    @KafkaListener(id = "loyalty-service.order-events", topics = Topics.ORDER_EVENTS)
    public void onOrderEvent(String message) {
        service.handleOrderEvent(reader.read(message));
    }

    @KafkaListener(id = "loyalty-service.review-events", topics = Topics.REVIEW_EVENTS)
    public void onReviewEvent(String message) {
        service.handleReviewEvent(reader.read(message));
    }
}
