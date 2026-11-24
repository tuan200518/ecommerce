package com.shop.order.messaging;

import com.shop.common.event.Topics;
import com.shop.support.kafka.EventReader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Các Kafka consumer của Order Service. id của listener cũng là consumer group id. */
@Component
public class OrderEventListeners {

    private final OrderSagaHandler handler;
    private final EventReader reader;

    public OrderEventListeners(OrderSagaHandler handler, EventReader reader) {
        this.handler = handler;
        this.reader = reader;
    }

    @KafkaListener(id = "order-service.inventory-events", topics = Topics.INVENTORY_EVENTS)
    public void onInventoryEvent(String message) {
        handler.handleInventoryEvent(reader.read(message));
    }

    @KafkaListener(id = "order-service.payment-events", topics = Topics.PAYMENT_EVENTS)
    public void onPaymentEvent(String message) {
        handler.handlePaymentEvent(reader.read(message));
    }

    @KafkaListener(id = "order-service.fraud-alert-events", topics = Topics.FRAUD_ALERT_EVENTS)
    public void onFraudEvent(String message) {
        handler.handleFraudEvent(reader.read(message));
    }

    @KafkaListener(id = "order-service.promotion-events", topics = Topics.PROMOTION_EVENTS)
    public void onPromotionEvent(String message) {
        handler.handlePromotionEvent(reader.read(message));
    }

    @KafkaListener(id = "order-service.shipping-events", topics = Topics.SHIPPING_EVENTS)
    public void onShippingEvent(String message) {
        handler.handleShippingEvent(reader.read(message));
    }

    @KafkaListener(id = "order-service.product-events", topics = Topics.PRODUCT_EVENTS)
    public void onProductEvent(String message) {
        handler.handleProductEvent(reader.read(message));
    }
}
