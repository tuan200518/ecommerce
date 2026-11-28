package com.shop.notification.alerting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Payload webhook của Alertmanager (version 4) – chỉ các trường cần dùng. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertmanagerWebhook(String status, String receiver, List<Alert> alerts, String externalURL) {

    /**
     * @param status firing | resolved
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Alert(String status, Map<String, String> labels, Map<String, String> annotations,
                        Instant startsAt, Instant endsAt, String generatorURL, String fingerprint) {

        public String label(String name) {
            return labels == null ? null : labels.get(name);
        }

        public String annotation(String name) {
            return annotations == null ? null : annotations.get(name);
        }
    }
}
