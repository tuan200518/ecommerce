package com.shop.payment.messaging;

import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.event.payload.OrderEvents.PaymentRequested;
import com.shop.payment.service.PaymentService;
import com.shop.support.kafka.EventReader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventListener {

    private final PaymentService service;
    private final EventReader reader;

    public PaymentEventListener(PaymentService service, EventReader reader) {
        this.service = service;
        this.reader = reader;
    }

    @KafkaListener(id = "payment-service.order-events", topics = Topics.ORDER_EVENTS)
    public void onOrderEvent(String message) {
        EventEnvelope env = reader.read(message);
        switch (env.eventType()) {
            case EventTypes.PAYMENT_REQUESTED -> service.process(env, reader.payload(env, PaymentRequested.class));
            case EventTypes.ORDER_CANCELLED -> {
                OrderStatusChanged p = reader.payload(env, OrderStatusChanged.class);
                service.refundIfPaid(env, p.orderId(), p.reason());
            }
            default -> {
            }
        }
    }
}
