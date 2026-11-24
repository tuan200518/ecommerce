package com.shop.shipping.messaging;

import com.shop.common.event.Topics;
import com.shop.shipping.service.ShippingService;
import com.shop.support.kafka.EventReader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ShippingEventListener {

    private final ShippingService service;
    private final EventReader reader;

    public ShippingEventListener(ShippingService service, EventReader reader) {
        this.service = service;
        this.reader = reader;
    }

    @KafkaListener(id = "shipping-service.order-events", topics = Topics.ORDER_EVENTS)
    public void onOrderEvent(String message) {
        service.handleOrderEvent(reader.read(message));
    }
}
