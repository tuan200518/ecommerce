package com.shop.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Quét định kỳ các đơn bị kẹt trong Saga (không nhận được phản hồi) và hủy chúng. */
@Component
public class OrderTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutJob.class);

    private final OrderService orderService;

    public OrderTimeoutJob(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${shop.order.timeout-scan-interval-ms:15000}", initialDelay = 15000)
    public void cancelExpiredOrders() {
        for (Long id : orderService.findExpiredOrderIds()) {
            try {
                orderService.expire(id);
                log.warn("Đơn #{} bị hủy do quá thời gian xử lý", id);
            } catch (Exception e) {
                // Có thể do đơn vừa được cập nhật song song (optimistic lock) → lần quét sau xử lý tiếp
                log.debug("Không thể hủy đơn #{}: {}", id, e.getMessage());
            }
        }
    }
}
