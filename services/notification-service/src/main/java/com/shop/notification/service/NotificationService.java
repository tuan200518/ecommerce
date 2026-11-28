package com.shop.notification.service;

import com.shop.common.event.EventEnvelope;
import com.shop.common.event.payload.NotificationEvents.NotificationRequested;
import com.shop.notification.domain.Notification;
import com.shop.notification.domain.NotificationRepository;
import com.shop.support.outbox.IdempotencyGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    static final String CONSUMER = "notification-service";

    public record NotificationDto(long id, String type, String title, String message, String referenceId,
                                  boolean read, boolean broadcast, Instant createdAt) {
        public static NotificationDto from(Notification n) {
            return new NotificationDto(n.getId(), n.getType(), n.getTitle(), n.getMessage(), n.getReferenceId(),
                    n.isRead(), n.getUserId() == null, n.getCreatedAt());
        }
    }

    private final NotificationRepository repository;
    private final IdempotencyGuard guard;
    private final SseHub hub;

    public NotificationService(NotificationRepository repository, IdempotencyGuard guard, SseHub hub) {
        this.repository = repository;
        this.guard = guard;
        this.hub = hub;
    }

    @Transactional
    public void handle(EventEnvelope env, NotificationRequested req) {
        if (!guard.firstTime(env, CONSUMER)) {
            return;
        }
        save(req);
    }

    /**
     * Thông báo trong ứng dụng cho mọi người thuộc một vai trò, không đi qua Kafka – dùng cho cảnh báo hệ thống
     * từ Alertmanager (lúc Kafka gặp sự cố, cảnh báo vẫn phải đến được quản trị viên).
     */
    @Transactional
    public NotificationDto notifyRole(String role, String type, String title, String message, String referenceId) {
        return save(new NotificationRequested(null, role, type, title, message, referenceId));
    }

    private NotificationDto save(NotificationRequested req) {
        Notification n = repository.save(new Notification(req.userId(), req.targetRole(), req.type(),
                truncate(req.title(), 255), truncate(req.message(), 1000), req.referenceId()));
        NotificationDto dto = NotificationDto.from(n);
        // Chỉ đẩy realtime sau khi commit thành công → không gửi thông báo "ma" nếu transaction rollback
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (req.userId() != null) {
                    hub.sendToUser(req.userId(), dto);
                    // Mô phỏng kênh email/SMS
                    log.info("[EMAIL -> user {}] {} - {}", req.userId(), req.title(), req.message());
                }
                if (req.targetRole() != null) {
                    hub.sendToRole(req.targetRole(), dto);
                    log.info("[BROADCAST -> {}] {} - {}", req.targetRole(), req.title(), req.message());
                }
            }
        });
        return dto;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
