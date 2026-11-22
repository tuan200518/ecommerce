package com.shop.analytics.dashboard;

import com.shop.analytics.model.AnalyticsModels.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Đẩy dữ liệu Dashboard qua Server-Sent Events.
 * Snapshot được gửi tối đa 1 lần/giây (throttle) khi có thay đổi → chịu được tải hàng nghìn sự kiện/giây;
 * cảnh báo được gửi ngay lập tức.
 */
@Component
public class DashboardBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(DashboardBroadcaster.class);

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final DashboardState state;

    public DashboardBroadcaster(DashboardState state) {
        this.state = state;
    }

    public SseEmitter connect() {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("snapshot").data(state.snapshot()));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    @Scheduled(fixedRateString = "${shop.analytics.broadcast-interval-ms:1000}")
    public void broadcastSnapshot() {
        if (emitters.isEmpty() || !state.consumeDirty()) {
            return;
        }
        send("snapshot", state.snapshot());
    }

    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        emitters.forEach(e -> {
            try {
                e.send(SseEmitter.event().comment("ping"));
            } catch (Exception ex) {
                emitters.remove(e);
            }
        });
    }

    public void alert(Alert alert) {
        send("alert", alert);
    }

    public int clients() {
        return emitters.size();
    }

    private void send(String name, Object data) {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name(name).data(data));
            } catch (Exception ex) {
                log.debug("Ngắt client SSE: {}", ex.getMessage());
                emitters.remove(e);
            }
        }
    }
}
