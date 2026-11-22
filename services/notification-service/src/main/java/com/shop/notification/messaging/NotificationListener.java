package com.shop.notification.messaging;

import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.NotificationEvents.NotificationRequested;
import com.shop.notification.service.NotificationService;
import com.shop.support.kafka.EventReader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationListener {

    private final NotificationService service;
    private final EventReader reader;

    public NotificationListener(NotificationService service, EventReader reader) {
        this.service = service;
        this.reader = reader;
    }

    @KafkaListener(id = "notification-service.notification-events", topics = Topics.NOTIFICATION_EVENTS)
    public void onNotification(String message) {
        EventEnvelope env = reader.read(message);
        if (env.is(EventTypes.NOTIFICATION_REQUESTED)) {
            service.handle(env, reader.payload(env, NotificationRequested.class));
        }
    }
}
