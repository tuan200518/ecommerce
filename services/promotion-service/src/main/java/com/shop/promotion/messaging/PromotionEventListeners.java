package com.shop.promotion.messaging;

import com.shop.common.event.Topics;
import com.shop.promotion.service.PromotionService;
import com.shop.support.kafka.EventReader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PromotionEventListeners {

    private final PromotionService service;
    private final EventReader reader;

    public PromotionEventListeners(PromotionService service, EventReader reader) {
        this.service = service;
        this.reader = reader;
    }

    @KafkaListener(id = "promotion-service.product-events", topics = Topics.PRODUCT_EVENTS)
    public void onProductEvent(String message) {
        service.handleProductEvent(reader.read(message));
    }

    @KafkaListener(id = "promotion-service.order-events", topics = Topics.ORDER_EVENTS)
    public void onOrderEvent(String message) {
        service.handleOrderEvent(reader.read(message));
    }

    @KafkaListener(id = "promotion-service.loyalty-events", topics = Topics.LOYALTY_EVENTS)
    public void onLoyaltyEvent(String message) {
        service.handleLoyaltyEvent(reader.read(message));
    }
}
