package com.shop.helpdesk.service;

import com.shop.helpdesk.domain.TicketRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Tác vụ định kỳ + metric cho Prometheus:
 * {@code shop_support_tickets_active}, {@code shop_support_tickets_overdue} (→ cảnh báo SupportTicketsOverdue qua
 * Alertmanager), {@code shop_support_tickets_unassigned}.
 */
@Component
@ConditionalOnProperty(prefix = "shop.support", name = "jobs-enabled", havingValue = "true", matchIfMissing = true)
public class SupportJobs {

    private static final Logger log = LoggerFactory.getLogger(SupportJobs.class);

    private final SupportService service;

    public SupportJobs(SupportService service, TicketRepository tickets, MeterRegistry meters) {
        this.service = service;
        Gauge.builder("shop.support.tickets.active", tickets,
                        t -> safe(() -> SupportService.ACTIVE.stream().mapToLong(t::countByStatus).sum()))
                .description("Yêu cầu hỗ trợ đang mở").register(meters);
        Gauge.builder("shop.support.tickets.overdue", tickets,
                        t -> safe(() -> t.countOverdue(SupportService.ACTIVE, Instant.now())))
                .description("Yêu cầu quá hạn phản hồi đầu tiên (SLA)").register(meters);
        Gauge.builder("shop.support.tickets.unassigned", tickets,
                        t -> safe(() -> t.countByStatusInAndAssigneeIdIsNull(SupportService.ACTIVE)))
                .description("Yêu cầu đang mở chưa có người xử lý").register(meters);
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void slaWatch() {
        try {
            int n = service.notifySlaBreaches();
            if (n > 0) {
                log.warn("{} yêu cầu hỗ trợ vừa quá hạn phản hồi", n);
            }
        } catch (Exception e) {
            log.warn("Kiểm tra SLA thất bại: {}", e.getMessage());
        }
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 600_000)
    public void autoClose() {
        try {
            int n = service.autoClose();
            if (n > 0) {
                log.info("Tự động đóng {} yêu cầu đã giải quyết", n);
            }
        } catch (Exception e) {
            log.warn("Tự động đóng yêu cầu thất bại: {}", e.getMessage());
        }
    }

    private static double safe(java.util.function.LongSupplier s) {
        try {
            return s.getAsLong();
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
