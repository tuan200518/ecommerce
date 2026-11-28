package com.shop.notification.alerting;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Các kênh gửi cảnh báo ra ngoài. Kênh chỉ bật khi được cấu hình; lỗi của một kênh không chặn các kênh khác.
 */
@Component
public class AlertChannels {

    /** Nội dung đã định dạng sẵn cho mọi kênh. */
    public record AlertMessage(String title, String text) {
    }

    public interface Channel {
        String name();

        void send(AlertMessage message);
    }

    private final AlertingProperties props;
    private final ObjectProvider<JavaMailSender> mail;
    private final RestClient http;
    private final boolean smtpConfigured;

    public AlertChannels(AlertingProperties props, ObjectProvider<JavaMailSender> mail, RestClient.Builder builder,
                         @Value("${spring.mail.host:}") String smtpHost) {
        this.props = props;
        this.smtpConfigured = smtpHost != null && !smtpHost.isBlank();
        this.mail = mail;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = builder.requestFactory(factory).build();
    }

    /** Các kênh đang bật theo cấu hình hiện tại. */
    public List<Channel> enabled() {
        List<Channel> channels = new ArrayList<>();
        if (props.slackWebhookUrl() != null) {
            channels.add(json("slack", props.slackWebhookUrl(), m -> Map.of("text", "*" + m.title() + "*\n" + m.text())));
        }
        if (props.discordWebhookUrl() != null) {
            // Discord giới hạn 2000 ký tự mỗi tin nhắn
            channels.add(json("discord", props.discordWebhookUrl(),
                    m -> Map.of("content", limit("**" + m.title() + "**\n" + m.text(), 1990))));
        }
        if (props.telegramBotToken() != null && props.telegramChatId() != null) {
            channels.add(json("telegram", props.telegramApiUrl() + "/bot" + props.telegramBotToken() + "/sendMessage",
                    m -> Map.of("chat_id", props.telegramChatId(), "text", limit(m.title() + "\n" + m.text(), 4000))));
        }
        JavaMailSender sender = mail.getIfAvailable();
        if (sender != null && smtpConfigured && !props.emailTo().isEmpty()) {
            channels.add(new Channel() {
                @Override
                public String name() {
                    return "email";
                }

                @Override
                public void send(AlertMessage m) {
                    SimpleMailMessage msg = new SimpleMailMessage();
                    msg.setFrom(props.emailFrom());
                    msg.setTo(props.emailTo().toArray(String[]::new));
                    msg.setSubject(m.title());
                    msg.setText(m.text());
                    sender.send(msg);
                }
            });
        }
        return channels;
    }

    /** Tên các kênh đang bật (không lộ URL/token) – hiển thị ở trang quản trị. */
    public List<String> enabledNames() {
        return enabled().stream().map(Channel::name).toList();
    }

    private Channel json(String name, String url, java.util.function.Function<AlertMessage, Object> body) {
        return new Channel() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void send(AlertMessage m) {
                http.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(body.apply(m)).retrieve().toBodilessEntity();
            }
        };
    }

    private static String limit(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
