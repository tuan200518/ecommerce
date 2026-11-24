package com.shop.audit.messaging;

import com.shop.audit.service.AuditService;
import com.shop.common.event.Topics;
import com.shop.support.kafka.EventReader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class AuditEventListener {

    private final AuditService service;
    private final EventReader reader;

    public AuditEventListener(AuditService service, EventReader reader) {
        this.service = service;
        this.reader = reader;
    }

    @KafkaListener(id = "audit-service.audit-events", topics = Topics.AUDIT_EVENTS)
    public void onAuditEvent(String message) {
        service.handleAuditEvent(reader.read(message));
    }
}
