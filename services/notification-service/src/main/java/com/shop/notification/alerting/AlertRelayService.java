package com.shop.notification.alerting;

import com.shop.common.security.Roles;
import com.shop.notification.alerting.AlertChannels.AlertMessage;
import com.shop.notification.alerting.AlertChannels.Channel;
import com.shop.notification.alerting.AlertmanagerWebhook.Alert;
import com.shop.notification.service.NotificationService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Nhận cảnh báo từ Alertmanager → (1) thông báo trong ứng dụng cho ADMIN (chuông + SSE), (2) gửi ra Slack / Discord /
 * Telegram / email tùy cấu hình, (3) lưu lịch sử kèm kết quả từng kênh. Lỗi một kênh chỉ được ghi nhận, không làm
 * webhook thất bại (tránh Alertmanager gửi lại liên tục và trùng thông báo ở các kênh đã gửi được).
 */
@Service
public class AlertRelayService {

    private static final Logger log = LoggerFactory.getLogger(AlertRelayService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy", Locale.of("vi", "VN"))
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final Map<String, String> SEVERITY_ICON = Map.of("critical", "🔴", "warning", "🟠", "info", "🔵");

    private final NotificationService notifications;
    private final AlertEventRepository events;
    private final AlertChannels channels;
    private final AlertingProperties props;
    private final MeterRegistry meters;

    public AlertRelayService(NotificationService notifications, AlertEventRepository events, AlertChannels channels,
                             AlertingProperties props, MeterRegistry meters) {
        this.notifications = notifications;
        this.events = events;
        this.channels = channels;
        this.props = props;
        this.meters = meters;
    }

    /**
     * Một webhook = một nhóm cảnh báo của Alertmanager. Nhiều cảnh báo cùng trạng thái được gộp thành MỘT tin nhắn
     * (vd. khởi động lại hệ thống: "ServiceDown – 12 service") để không gửi hàng chục email/tin chat cùng lúc.
     */
    public List<AlertEvent> receive(AlertmanagerWebhook hook) {
        List<AlertEvent> saved = new ArrayList<>();
        if (hook == null || hook.alerts() == null) {
            return saved;
        }
        Map<Boolean, List<Alert>> byResolved = hook.alerts().stream()
                .collect(java.util.stream.Collectors.partitioningBy(a -> "resolved".equalsIgnoreCase(a.status())));
        for (List<Alert> group : List.of(byResolved.get(false), byResolved.get(true))) {
            if (group.size() == 1) {
                saved.add(relay(group.get(0)));
            } else if (group.size() > 1) {
                saved.add(relayGroup(group));
            }
        }
        return saved;
    }

    AlertEvent relayGroup(List<Alert> alerts) {
        Alert first = alerts.get(0);
        boolean resolved = "resolved".equalsIgnoreCase(first.status());
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        alerts.forEach(a -> names.add(orDefault(a.label("alertname"), "Alert")));
        String name = names.size() == 1 ? names.iterator().next() : "Nhiều cảnh báo";
        String severity = alerts.stream().map(a -> orDefault(a.label("severity"), "warning").toLowerCase(Locale.ROOT))
                .min(java.util.Comparator.comparingInt(s -> switch (s) {
                    case "critical" -> 0;
                    case "warning" -> 1;
                    default -> 2;
                })).orElse("warning");
        String summary = names.size() == 1 ? name : String.join(", ", names);
        String title = (resolved ? "✅ Đã khôi phục: " : SEVERITY_ICON.getOrDefault(severity, "🟠") + " ["
                + severity.toUpperCase(Locale.ROOT) + "] ") + summary + " – " + alerts.size() + " đối tượng";
        StringBuilder text = new StringBuilder();
        alerts.stream().limit(25).forEach(a -> text.append("• ").append(orDefault(firstNonNull(a.label("service"),
                        a.label("application"), a.label("rule"), a.label("type"), a.label("instance")), "?"))
                .append(": ").append(orDefault(a.annotation("summary"), orDefault(a.label("alertname"), ""))).append('\n'));
        if (alerts.size() > 25) {
            text.append("… và ").append(alerts.size() - 25).append(" đối tượng khác\n");
        }
        alerts.stream().map(Alert::startsAt).filter(java.util.Objects::nonNull).min(Instant::compareTo)
                .ifPresent(t -> text.append("Bắt đầu: ").append(TIME.format(t)));
        if (props.dashboardUrl() != null) {
            text.append("\nDashboard: ").append(props.dashboardUrl());
        }
        return deliver(name, resolved, severity, alerts.size() + " đối tượng",
                new AlertMessage(title, text.toString().trim()), first.fingerprint(), first.startsAt());
    }

    /** Gửi một cảnh báo thử qua mọi kênh đang bật (kiểm tra cấu hình). */
    public AlertEvent sendTest(String requestedBy) {
        Alert test = new Alert("firing", Map.of("alertname", "TestAlert", "severity", "info", "service", "notification-service"),
                Map.of("summary", "Cảnh báo thử nghiệm", "description", "Gửi bởi " + requestedBy
                        + " từ trang Lịch sử cảnh báo – nếu nhận được tin này, kênh đã cấu hình đúng."),
                Instant.now(), null, null, "test-" + Instant.now().toEpochMilli());
        return relay(test);
    }

    AlertEvent relay(Alert alert) {
        String name = orDefault(alert.label("alertname"), "Alert");
        boolean resolved = "resolved".equalsIgnoreCase(alert.status());
        String severity = orDefault(alert.label("severity"), "warning").toLowerCase(Locale.ROOT);
        String target = firstNonNull(alert.label("service"), alert.label("application"), alert.label("rule"),
                alert.label("type"), alert.label("instance"));
        AlertMessage msg = format(alert, name, resolved, severity, target);
        return deliver(name, resolved, severity, target, msg, alert.fingerprint(), alert.startsAt());
    }

    /** Gửi một tin nhắn đã định dạng tới mọi kênh và lưu lịch sử. */
    private AlertEvent deliver(String name, boolean resolved, String severity, String target, AlertMessage msg,
                               String fingerprint, Instant startsAt) {
        List<String> deliveries = new ArrayList<>();
        try {
            notifications.notifyRole(Roles.ADMIN, resolved ? "SYSTEM_ALERT_RESOLVED" : "SYSTEM_ALERT",
                    msg.title(), msg.text(), "alert:" + name);
            deliveries.add("in-app: OK");
        } catch (Exception e) {
            log.warn("Không tạo được thông báo trong ứng dụng cho cảnh báo {}: {}", name, e.getMessage());
            deliveries.add("in-app: lỗi " + e.getMessage());
        }
        for (Channel channel : channels.enabled()) {
            try {
                channel.send(msg);
                deliveries.add(channel.name() + ": OK");
                meters.counter("shop.alerting.deliveries", "channel", channel.name(), "result", "ok").increment();
            } catch (Exception e) {
                log.warn("Gửi cảnh báo {} qua {} thất bại: {}", name, channel.name(), e.getMessage());
                deliveries.add(channel.name() + ": lỗi " + abbreviate(e.getMessage(), 120));
                meters.counter("shop.alerting.deliveries", "channel", channel.name(), "result", "error").increment();
            }
        }
        AlertEvent event = new AlertEvent(name, resolved ? "resolved" : "firing", severity, target,
                abbreviate(msg.title(), 255), abbreviate(msg.text(), 2000), fingerprint, startsAt);
        event.recordDeliveries(String.join("; ", deliveries));
        log.info("Cảnh báo {} [{}] {} → {}", name, event.getStatus(), target, event.getDeliveries());
        return events.save(event);
    }

    AlertMessage format(Alert alert, String name, boolean resolved, String severity, String target) {
        String summary = orDefault(alert.annotation("summary"), name);
        String title = resolved
                ? "✅ Đã khôi phục: " + summary
                : SEVERITY_ICON.getOrDefault(severity, "🟠") + " [" + severity.toUpperCase(Locale.ROOT) + "] " + summary;
        StringBuilder text = new StringBuilder();
        if (alert.annotation("description") != null) {
            text.append(alert.annotation("description")).append('\n');
        }
        text.append("Cảnh báo: ").append(name);
        if (target != null) {
            text.append(" · Đối tượng: ").append(target);
        }
        text.append('\n');
        if (alert.startsAt() != null) {
            text.append("Bắt đầu: ").append(TIME.format(alert.startsAt()));
        }
        if (resolved && alert.endsAt() != null && alert.endsAt().isAfter(Instant.EPOCH.plusSeconds(1))) {
            text.append(" · Kết thúc: ").append(TIME.format(alert.endsAt()));
        }
        if (props.dashboardUrl() != null) {
            text.append("\nDashboard: ").append(props.dashboardUrl());
        }
        return new AlertMessage(title, text.toString().trim());
    }

    private static String orDefault(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String abbreviate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
