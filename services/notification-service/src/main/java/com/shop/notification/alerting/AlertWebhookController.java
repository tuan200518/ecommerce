package com.shop.notification.alerting;

import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.support.web.Access;
import com.shop.support.web.ApiException;
import com.shop.support.web.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
public class AlertWebhookController {

    public record AlertEventDto(long id, String alertName, String status, String severity, String target, String title,
                                String message, Instant startsAt, Instant receivedAt, String deliveries) {
        static AlertEventDto from(AlertEvent e) {
            return new AlertEventDto(e.getId(), e.getAlertName(), e.getStatus(), e.getSeverity(), e.getTarget(),
                    e.getTitle(), e.getMessage(), e.getStartsAt(), e.getReceivedAt(), e.getDeliveries());
        }
    }

    private final AlertRelayService relay;
    private final AlertEventRepository events;
    private final AlertChannels channels;
    private final AlertingProperties props;

    public AlertWebhookController(AlertRelayService relay, AlertEventRepository events, AlertChannels channels,
                                  AlertingProperties props) {
        this.relay = relay;
        this.events = events;
        this.channels = channels;
        this.props = props;
    }

    /**
     * Webhook nội bộ cho Alertmanager. Không có route qua API Gateway (Gateway chỉ định tuyến /api/**) và cổng của
     * service không publish ra host; vẫn kiểm tra Bearer token để chặn request giả trong mạng nội bộ.
     */
    @PostMapping("/internal/alerts")
    public Map<String, Integer> webhook(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                        @RequestBody AlertmanagerWebhook hook) {
        if (props.webhookToken() == null || !constantTimeEquals("Bearer " + props.webhookToken(), auth)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Token webhook không hợp lệ");
        }
        return Map.of("received", relay.receive(hook).size());
    }

    // --------------------------------------------- Quản trị (ADMIN)

    @GetMapping("/api/notifications/admin/alerts")
    public PageResponse<AlertEventDto> history(AuthenticatedUser user, @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "30") int size) {
        Access.requireAnyRole(user, Roles.ADMIN);
        return PageResponse.of(events.findAllByOrderByReceivedAtDesc(
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))), AlertEventDto::from);
    }

    /** Kênh đang bật (chỉ tên, không lộ URL/token). */
    @GetMapping("/api/notifications/admin/alerts/channels")
    public Map<String, List<String>> enabledChannels(AuthenticatedUser user) {
        Access.requireAnyRole(user, Roles.ADMIN);
        List<String> names = new java.util.ArrayList<>(List.of("in-app"));
        names.addAll(channels.enabledNames());
        return Map.of("channels", names);
    }

    @PostMapping("/api/notifications/admin/alerts/test")
    public AlertEventDto test(AuthenticatedUser user) {
        Access.requireAnyRole(user, Roles.ADMIN);
        return AlertEventDto.from(relay.sendTest(user.email()));
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
