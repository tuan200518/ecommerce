package com.shop.notification.alerting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Lịch sử cảnh báo đã nhận từ Alertmanager và kết quả gửi tới từng kênh. */
@Entity
@Table(name = "alert_events", indexes = @Index(name = "idx_alert_received", columnList = "receivedAt"))
public class AlertEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String alertName;

    /** firing | resolved */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 16)
    private String severity;

    @Column(length = 128)
    private String target;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 2000)
    private String message;

    @Column(length = 64)
    private String fingerprint;

    private Instant startsAt;

    @Column(nullable = false)
    private Instant receivedAt;

    /** vd. "in-app: OK; slack: OK; email: lỗi Connection refused" */
    @Column(length = 1000)
    private String deliveries;

    protected AlertEvent() {
    }

    public AlertEvent(String alertName, String status, String severity, String target, String title, String message,
                      String fingerprint, Instant startsAt) {
        this.alertName = alertName;
        this.status = status;
        this.severity = severity;
        this.target = target;
        this.title = title;
        this.message = message;
        this.fingerprint = fingerprint;
        this.startsAt = startsAt;
        this.receivedAt = Instant.now();
    }

    public void recordDeliveries(String deliveries) {
        this.deliveries = deliveries == null || deliveries.length() <= 1000 ? deliveries : deliveries.substring(0, 999);
    }

    public Long getId() {
        return id;
    }

    public String getAlertName() {
        return alertName;
    }

    public String getStatus() {
        return status;
    }

    public String getSeverity() {
        return severity;
    }

    public String getTarget() {
        return target;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getDeliveries() {
        return deliveries;
    }
}
