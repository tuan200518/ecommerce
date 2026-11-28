package com.shop.helpdesk.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Kênh realtime (SSE) của chat hỗ trợ:
 * <ul>
 *   <li>kênh theo yêu cầu – khách và nhân viên đang mở cùng một yêu cầu nhận tin nhắn mới / thay đổi trạng thái;</li>
 *   <li>kênh hàng đợi – nhân viên CSKH nhận yêu cầu mới, tin nhắn mới của khách để cập nhật danh sách.</li>
 * </ul>
 * Chỉ đẩy sau khi transaction commit (xem {@code SupportService}). Một instance; khi scale ngang cần phát qua Kafka
 * (support-events) tới mọi instance.
 */
@Component
public class SupportHub {

    private static final Logger log = LoggerFactory.getLogger(SupportHub.class);
    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final Map<Long, List<SseEmitter>> tickets = new ConcurrentHashMap<>();
    private final List<SseEmitter> queue = new CopyOnWriteArrayList<>();

    public SseEmitter subscribeTicket(long ticketId) {
        List<SseEmitter> list = tickets.computeIfAbsent(ticketId, id -> new CopyOnWriteArrayList<>());
        return register(list, () -> {
            if (list.isEmpty()) {
                tickets.remove(ticketId, list);
            }
        });
    }

    public SseEmitter subscribeQueue() {
        return register(queue, () -> { });
    }

    public void toTicket(long ticketId, String event, Object payload) {
        List<SseEmitter> list = tickets.get(ticketId);
        if (list != null) {
            send(list, event, payload);
        }
    }

    public void toQueue(String event, Object payload) {
        send(queue, event, payload);
    }

    public int connections() {
        return queue.size() + tickets.values().stream().mapToInt(List::size).sum();
    }

    /** Heartbeat giữ kết nối qua Nginx / Gateway. */
    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        tickets.values().forEach(list -> list.forEach(e -> ping(list, e)));
        queue.forEach(e -> ping(queue, e));
    }

    private SseEmitter register(List<SseEmitter> list, Runnable cleanup) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        list.add(emitter);
        Runnable remove = () -> {
            list.remove(emitter);
            cleanup.run();
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    private void send(List<SseEmitter> list, String event, Object payload) {
        for (SseEmitter e : list) {
            try {
                e.send(SseEmitter.event().name(event).data(payload));
            } catch (Exception ex) {
                log.debug("Ngắt kết nối SSE hỗ trợ: {}", ex.getMessage());
                list.remove(e);
            }
        }
    }

    private static void ping(List<SseEmitter> list, SseEmitter e) {
        try {
            e.send(SseEmitter.event().comment("ping"));
        } catch (Exception ex) {
            list.remove(e);
        }
    }
}
