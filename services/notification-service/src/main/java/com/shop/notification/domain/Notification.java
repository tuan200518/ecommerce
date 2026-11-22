package com.shop.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notif_user", columnList = "userId,id"),
        @Index(name = "idx_notif_role", columnList = "targetRole,id")
})
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @Column(length = 32)
    private String targetRole;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(nullable = false)
    private String title;

    @Column(length = 1000)
    private String message;

    @Column(length = 128)
    private String referenceId;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(nullable = false)
    private Instant createdAt;

    protected Notification() {
    }

    public Notification(Long userId, String targetRole, String type, String title, String message, String referenceId) {
        this.userId = userId;
        this.targetRole = targetRole;
        this.type = type;
        this.title = title;
        this.message = message;
        this.referenceId = referenceId;
        this.createdAt = Instant.now();
    }

    public void markRead() {
        this.read = true;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTargetRole() {
        return targetRole;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public boolean isRead() {
        return read;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
