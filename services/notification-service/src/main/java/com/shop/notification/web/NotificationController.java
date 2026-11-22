package com.shop.notification.web;

import com.shop.common.security.AuthenticatedUser;
import com.shop.notification.domain.Notification;
import com.shop.notification.domain.NotificationRepository;
import com.shop.notification.service.NotificationService.NotificationDto;
import com.shop.notification.service.SseHub;
import com.shop.support.web.ApiException;
import com.shop.support.web.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRepository repository;
    private final SseHub hub;

    public NotificationController(NotificationRepository repository, SseHub hub) {
        this.repository = repository;
        this.hub = hub;
    }

    /** Kênh realtime: trình duyệt mở EventSource tới endpoint này (qua API Gateway). */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(AuthenticatedUser user) {
        return hub.connect(user);
    }

    @GetMapping
    public PageResponse<NotificationDto> list(AuthenticatedUser user,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        Set<String> roles = user.roles().isEmpty() ? Set.of("_none_") : user.roles();
        return PageResponse.of(repository.findVisible(user.userId(), roles,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))), NotificationDto::from);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unread(AuthenticatedUser user) {
        return Map.of("count", repository.countByUserIdAndReadFalse(user.userId()));
    }

    @PostMapping("/{id}/read")
    @Transactional
    public NotificationDto markRead(AuthenticatedUser user, @PathVariable long id) {
        Notification n = repository.findById(id)
                .filter(x -> x.getUserId() != null && x.getUserId().equals(user.userId()))
                .orElseThrow(() -> ApiException.notFound("Không tìm thấy thông báo"));
        n.markRead();
        return NotificationDto.from(n);
    }

    @PostMapping("/read-all")
    @Transactional
    public Map<String, Integer> markAllRead(AuthenticatedUser user) {
        return Map.of("updated", repository.markAllRead(user.userId()));
    }
}
