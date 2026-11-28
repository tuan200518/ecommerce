package com.shop.helpdesk.messaging;

import com.shop.common.event.Topics;
import com.shop.helpdesk.service.SupportService;
import com.shop.support.kafka.EventReader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class SupportEventListener {

    private final SupportService service;
    private final EventReader reader;

    public SupportEventListener(SupportService service, EventReader reader) {
        this.service = service;
        this.reader = reader;
    }

    /** Read-model đơn hàng + tin nhắn hệ thống khi đơn liên quan tới yêu cầu đang mở thay đổi trạng thái. */
    @KafkaListener(id = "support-service.order-events", topics = Topics.ORDER_EVENTS)
    public void onOrderEvent(String message) {
        service.handleOrderEvent(reader.read(message));
    }
}
