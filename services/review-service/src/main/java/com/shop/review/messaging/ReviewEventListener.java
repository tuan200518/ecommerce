package com.shop.review.messaging;

import com.shop.common.event.Topics;
import com.shop.review.service.ReviewService;
import com.shop.support.kafka.EventReader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ReviewEventListener {

    private final ReviewService service;
    private final EventReader reader;

    public ReviewEventListener(ReviewService service, EventReader reader) {
        this.service = service;
        this.reader = reader;
    }

    @KafkaListener(id = "review-service.order-events", topics = Topics.ORDER_EVENTS)
    public void onOrderEvent(String message) {
        service.handleOrderEvent(reader.read(message));
    }
}
