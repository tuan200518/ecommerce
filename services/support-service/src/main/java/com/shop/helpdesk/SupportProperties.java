package com.shop.helpdesk;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param slaUrgent / slaHigh / slaNormal / slaLow thời hạn phản hồi đầu tiên theo mức ưu tiên
 * @param autoCloseAfter    yêu cầu đã giải quyết, khách không phản hồi thêm sau khoảng này → tự đóng
 * @param maxOpenPerCustomer số yêu cầu đang mở tối đa mỗi khách (chống spam)
 * @param maxEventAge       chỉ thêm tin nhắn hệ thống cho sự kiện đơn hàng mới (khi đọc lại topic từ đầu)
 */
@ConfigurationProperties(prefix = "shop.support")
public record SupportProperties(Duration slaUrgent, Duration slaHigh, Duration slaNormal, Duration slaLow,
                                Duration autoCloseAfter, Integer maxOpenPerCustomer, Duration maxEventAge) {

    public SupportProperties {
        slaUrgent = slaUrgent == null ? Duration.ofMinutes(15) : slaUrgent;
        slaHigh = slaHigh == null ? Duration.ofMinutes(30) : slaHigh;
        slaNormal = slaNormal == null ? Duration.ofHours(2) : slaNormal;
        slaLow = slaLow == null ? Duration.ofHours(8) : slaLow;
        autoCloseAfter = autoCloseAfter == null ? Duration.ofDays(3) : autoCloseAfter;
        maxOpenPerCustomer = maxOpenPerCustomer == null ? 5 : maxOpenPerCustomer;
        maxEventAge = maxEventAge == null ? Duration.ofMinutes(10) : maxEventAge;
    }

    public Duration slaFor(com.shop.helpdesk.domain.Ticket.Priority priority) {
        return switch (priority) {
            case URGENT -> slaUrgent;
            case HIGH -> slaHigh;
            case NORMAL -> slaNormal;
            case LOW -> slaLow;
        };
    }
}
