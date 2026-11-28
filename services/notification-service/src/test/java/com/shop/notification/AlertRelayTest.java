package com.shop.notification;

import com.shop.common.security.AuthHeaders;
import com.shop.notification.alerting.AlertEventRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Alertmanager webhook → thông báo trong ứng dụng + Slack/Discord/Telegram/email, lỗi một kênh không chặn kênh khác. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:alerts;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.listener.auto-startup=false",
        "shop.outbox.relay-enabled=false",
        "shop.kafka.create-topics=false",
        "spring.mail.host=localhost",
        "shop.alerting.webhook-token=test-token",
        "shop.alerting.email-to=ops@shop.local,admin@shop.local",
        "shop.alerting.telegram-bot-token=BOT123",
        "shop.alerting.telegram-chat-id=-100",
        "shop.alerting.dashboard-url=http://grafana.local"
})
@AutoConfigureMockMvc
class AlertRelayTest {

    static final HttpServer SERVER;
    static final Map<String, List<String>> RECEIVED = new ConcurrentHashMap<>();
    static volatile boolean discordFails;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SERVER.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            RECEIVED.computeIfAbsent(path, k -> new CopyOnWriteArrayList<>()).add(body);
            int code = path.startsWith("/discord") && discordFails ? 500 : 200;
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        SERVER.start();
    }

    @DynamicPropertySource
    static void channels(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        registry.add("shop.alerting.slack-webhook-url", () -> base + "/slack");
        registry.add("shop.alerting.discord-webhook-url", () -> base + "/discord");
        registry.add("shop.alerting.telegram-api-url", () -> base + "/telegram");
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    static final List<SimpleMailMessage> MAILS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class MailConfig {
        @Bean
        JavaMailSender javaMailSender() {
            return new JavaMailSenderImpl() {
                @Override
                public void send(SimpleMailMessage... messages) {
                    MAILS.addAll(List.of(messages));
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    AlertEventRepository events;
    @Autowired
    JdbcTemplate jdbc;

    private static final String FIRING = """
            {"version":"4","status":"firing","receiver":"shop","alerts":[{"status":"firing",
             "labels":{"alertname":"ServiceDown","severity":"critical","service":"payment-service"},
             "annotations":{"summary":"Service payment-service không phản hồi","description":"Không scrape được trong 1 phút."},
             "startsAt":"2026-11-28T03:00:00Z","endsAt":"0001-01-01T00:00:00Z","fingerprint":"abc123"}]}""";

    @BeforeEach
    void reset() {
        RECEIVED.clear();
        MAILS.clear();
        discordFails = false;
        events.deleteAll();
        jdbc.update("DELETE FROM notifications");
    }

    @Test
    void webhookRequiresToken() throws Exception {
        mvc.perform(post("/internal/alerts").contentType(MediaType.APPLICATION_JSON).content(FIRING))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/alerts").header("Authorization", "Bearer sai").contentType(MediaType.APPLICATION_JSON)
                .content(FIRING)).andExpect(status().isUnauthorized());
        assertThat(events.count()).isZero();
    }

    @Test
    void firingAlert_isDeliveredToEveryChannel_andAdminsInApp() throws Exception {
        mvc.perform(post("/internal/alerts").header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(FIRING))
                .andExpect(status().isOk()).andExpect(jsonPath("$.received").value(1));

        assertThat(RECEIVED.get("/slack")).singleElement().asString().contains("[CRITICAL] Service payment-service không phản hồi");
        assertThat(RECEIVED.get("/discord")).singleElement().asString().contains("\"content\"");
        assertThat(RECEIVED.get("/telegram/botBOT123/sendMessage")).singleElement().asString()
                .contains("\"chat_id\":\"-100\"").contains("Dashboard: http://grafana.local");
        assertThat(MAILS).singleElement().satisfies(m -> {
            assertThat(m.getTo()).containsExactly("ops@shop.local", "admin@shop.local");
            assertThat(m.getSubject()).contains("Service payment-service không phản hồi");
            assertThat(m.getText()).contains("Đối tượng: payment-service").contains("10:00:00 28/11/2026");
        });
        // Thông báo trong ứng dụng cho mọi ADMIN
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE target_role = 'ADMIN' AND type = 'SYSTEM_ALERT'",
                Integer.class)).isEqualTo(1);
        assertThat(events.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo("firing");
            assertThat(e.getDeliveries()).isEqualTo("in-app: OK; slack: OK; discord: OK; telegram: OK; email: OK");
        });
    }

    @Test
    void oneFailingChannel_doesNotBlockOthers_andResolvedIsFormatted() throws Exception {
        discordFails = true;
        String resolved = FIRING.replace("\"status\":\"firing\"", "\"status\":\"resolved\"")
                .replace("0001-01-01T00:00:00Z", "2026-11-28T03:05:00Z");
        mvc.perform(post("/internal/alerts").header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).content(resolved)).andExpect(status().isOk());

        assertThat(RECEIVED.get("/slack")).singleElement().asString().contains("Đã khôi phục");
        assertThat(MAILS).hasSize(1);
        assertThat(events.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo("resolved");
            assertThat(e.getDeliveries()).contains("slack: OK").contains("discord: lỗi").contains("email: OK");
        });
    }

    @Test
    void groupedAlerts_becomeOneMessageListingEveryTarget() throws Exception {
        String group = """
                {"status":"firing","alerts":[
                 {"status":"firing","labels":{"alertname":"ServiceDown","severity":"critical","service":"auth-service"},
                  "annotations":{"summary":"Service auth-service không phản hồi"},"startsAt":"2026-11-28T03:00:00Z"},
                 {"status":"firing","labels":{"alertname":"ServiceDown","severity":"critical","service":"order-service"},
                  "annotations":{"summary":"Service order-service không phản hồi"},"startsAt":"2026-11-28T03:00:05Z"},
                 {"status":"firing","labels":{"alertname":"ServiceDown","severity":"critical","service":"cart-service"},
                  "annotations":{"summary":"Service cart-service không phản hồi"},"startsAt":"2026-11-28T03:00:07Z"}]}""";
        mvc.perform(post("/internal/alerts").header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(group))
                .andExpect(status().isOk()).andExpect(jsonPath("$.received").value(1));

        assertThat(MAILS).singleElement().satisfies(m -> {
            assertThat(m.getSubject()).isEqualTo("🔴 [CRITICAL] ServiceDown – 3 đối tượng");
            assertThat(m.getText()).contains("• auth-service").contains("• order-service").contains("• cart-service")
                    .contains("Bắt đầu: 10:00:00 28/11/2026");
        });
        assertThat(RECEIVED.get("/slack")).hasSize(1);
        assertThat(events.findAll()).singleElement().satisfies(e -> assertThat(e.getTarget()).isEqualTo("3 đối tượng"));
    }

    @Test
    void adminCanListChannels_andSendTest_customerCannot() throws Exception {
        mvc.perform(get("/api/notifications/admin/alerts/channels")
                        .header(AuthHeaders.USER_ID, "1").header(AuthHeaders.USER_ROLES, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels.length()").value(5));
        mvc.perform(post("/api/notifications/admin/alerts/test")
                        .header(AuthHeaders.USER_ID, "1").header(AuthHeaders.USER_EMAIL, "admin@shop.local")
                        .header(AuthHeaders.USER_ROLES, "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.alertName").value("TestAlert"));
        assertThat(RECEIVED.get("/slack")).singleElement().asString().contains("Cảnh báo thử nghiệm");
        mvc.perform(get("/api/notifications/admin/alerts")
                        .header(AuthHeaders.USER_ID, "9").header(AuthHeaders.USER_ROLES, "CUSTOMER"))
                .andExpect(status().isForbidden());
    }
}
