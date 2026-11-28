package com.shop.notification.alerting;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Chuyển tiếp cảnh báo từ Alertmanager. Kênh nào được cấu hình (khác rỗng) thì được bật.
 *
 * @param webhookToken     Bearer token Alertmanager phải gửi kèm (phải trùng với infra/alertmanager/alertmanager.yml)
 * @param slackWebhookUrl  Slack Incoming Webhook
 * @param discordWebhookUrl Discord channel webhook
 * @param telegramBotToken / telegramChatId  bot Telegram và chat/nhóm nhận cảnh báo
 * @param emailTo          danh sách email nhận cảnh báo (gửi qua SMTP của spring.mail.*)
 * @param emailFrom        địa chỉ người gửi
 * @param dashboardUrl     đường dẫn Grafana gắn vào nội dung cảnh báo
 * @param telegramApiUrl   cho phép đổi API Telegram (test)
 */
@ConfigurationProperties(prefix = "shop.alerting")
public record AlertingProperties(String webhookToken, String slackWebhookUrl, String discordWebhookUrl,
                                 String telegramBotToken, String telegramChatId, List<String> emailTo,
                                 String emailFrom, String dashboardUrl, String telegramApiUrl) {

    public AlertingProperties {
        webhookToken = blankToNull(webhookToken);
        slackWebhookUrl = blankToNull(slackWebhookUrl);
        discordWebhookUrl = blankToNull(discordWebhookUrl);
        telegramBotToken = blankToNull(telegramBotToken);
        telegramChatId = blankToNull(telegramChatId);
        emailTo = emailTo == null ? List.of() : emailTo.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        emailFrom = emailFrom == null || emailFrom.isBlank() ? "alerts@shop.local" : emailFrom;
        dashboardUrl = blankToNull(dashboardUrl);
        telegramApiUrl = telegramApiUrl == null || telegramApiUrl.isBlank() ? "https://api.telegram.org" : telegramApiUrl;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
