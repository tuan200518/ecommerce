package com.shop.notification.service;

import com.shop.common.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Quản lý các kết nối SSE đang mở và đẩy thông báo tới đúng người nhận. */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    private record Client(long userId, Set<String> roles, SseEmitter emitter) {
    }

    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();

    public SseEmitter connect(AuthenticatedUser user) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        Client client = new Client(user.userId(), user.roles(), emitter);
        clients.add(client);
        emitter.onCompletion(() -> clients.remove(client));
        emitter.onTimeout(() -> clients.remove(client));
        emitter.onError(e -> clients.remove(client));
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            clients.remove(client);
        }
        return emitter;
    }

    public void sendToUser(long userId, Object payload) {
        clients.stream().filter(c -> c.userId() == userId).forEach(c -> send(c, payload));
    }

    public void sendToRole(String role, Object payload) {
        clients.stream().filter(c -> c.roles().contains(role)).forEach(c -> send(c, payload));
    }

    public int connectedClients() {
        return clients.size();
    }

    /** Heartbeat giữ kết nối qua proxy/gateway. */
    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        clients.forEach(c -> {
            try {
                c.emitter().send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                clients.remove(c);
            }
        });
    }

    private void send(Client c, Object payload) {
        try {
            c.emitter().send(SseEmitter.event().name("notification").data(payload));
        } catch (Exception e) {
            log.debug("Ngắt kết nối SSE của user {}: {}", c.userId(), e.getMessage());
            clients.remove(c);
        }
    }
}
