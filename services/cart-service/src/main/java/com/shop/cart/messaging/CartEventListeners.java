package com.shop.cart.messaging;

import com.shop.cart.service.CartService;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.OrderEvents.OrderCreated;
import com.shop.common.event.payload.ProductEvents.ProductChanged;
import com.shop.support.kafka.EventReader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CartEventListeners {

    private final CartService service;
    private final EventReader reader;

    public CartEventListeners(CartService service, EventReader reader) {
        this.service = service;
        this.reader = reader;
    }

    @KafkaListener(id = "cart-service.product-events", topics = Topics.PRODUCT_EVENTS)
    public void onProductEvent(String message) {
        EventEnvelope env = reader.read(message);
        service.applyProduct(reader.payload(env, ProductChanged.class), env.is(EventTypes.PRODUCT_DISCONTINUED));
    }

    @KafkaListener(id = "cart-service.order-events", topics = Topics.ORDER_EVENTS)
    public void onOrderEvent(String message) {
        EventEnvelope env = reader.read(message);
        if (env.is(EventTypes.ORDER_CREATED)) {
            service.onOrderCreated(env, reader.payload(env, OrderCreated.class));
        }
    }
}
