package com.shop.inventory.messaging;

import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.OrderEvents.OrderCreated;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.event.payload.ProductEvents.ProductChanged;
import com.shop.inventory.service.InsufficientStockException;
import com.shop.inventory.service.InventoryService;
import com.shop.support.kafka.EventReader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryEventListeners {

    private final InventoryService service;
    private final EventReader reader;

    public InventoryEventListeners(InventoryService service, EventReader reader) {
        this.service = service;
        this.reader = reader;
    }

    @KafkaListener(id = "inventory-service.order-events", topics = Topics.ORDER_EVENTS)
    public void onOrderEvent(String message) {
        EventEnvelope env = reader.read(message);
        switch (env.eventType()) {
            case EventTypes.ORDER_CREATED -> {
                OrderCreated order = reader.payload(env, OrderCreated.class);
                try {
                    service.reserve(env, order);
                } catch (InsufficientStockException e) {
                    service.reject(env, order, e);
                }
            }
            case EventTypes.ORDER_CONFIRMED ->
                    service.commit(env, reader.payload(env, OrderStatusChanged.class).orderId());
            case EventTypes.ORDER_CANCELLED ->
                    service.release(env, reader.payload(env, OrderStatusChanged.class).orderId());
            default -> {
            }
        }
    }

    @KafkaListener(id = "inventory-service.product-events", topics = Topics.PRODUCT_EVENTS)
    public void onProductEvent(String message) {
        EventEnvelope env = reader.read(message);
        service.applyProductEvent(env, reader.payload(env, ProductChanged.class));
    }
}
