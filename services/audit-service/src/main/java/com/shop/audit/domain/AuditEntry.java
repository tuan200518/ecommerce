package com.shop.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Một dòng nhật ký kiểm toán – chỉ thêm, không bao giờ sửa/xóa qua API. */
@Entity
@Table(name = "audit_entries",
        uniqueConstraints = @UniqueConstraint(name = "uk_audit_event", columnNames = "eventId"),
        indexes = {
                @Index(name = "idx_audit_time", columnList = "occurredAt"),
                @Index(name = "idx_audit_actor", columnList = "actorEmail,occurredAt"),
                @Index(name = "idx_audit_action", columnList = "action,occurredAt"),
                @Index(name = "idx_audit_ip", columnList = "clientIp,occurredAt")
        })
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** eventId của EventEnvelope → chống ghi trùng khi Kafka gửi lại. */
    @Column(nullable = false, length = 64)
    private String eventId;

    @Column(nullable = false)
    private Instant occurredAt;

    /** Service phát sinh thao tác. */
    @Column(nullable = false, length = 64)
    private String source;

    @Column(length = 64)
    private String correlationId;

    private Long actorId;

    @Column(length = 255)
    private String actorEmail;

    @Column(length = 128)
    private String actorRoles;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(nullable = false, length = 32)
    private String resourceType;

    @Column(length = 64)
    private String resourceId;

    @Column(nullable = false, length = 16)
    private String outcome;

    @Column(length = 1000)
    private String detail;

    @Column(length = 64)
    private String clientIp;

    protected AuditEntry() {
    }

    public AuditEntry(String eventId, Instant occurredAt, String source, String correlationId, Long actorId,
                      String actorEmail, String actorRoles, String action, String resourceType, String resourceId,
                      String outcome, String detail, String clientIp) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.source = source;
        this.correlationId = correlationId;
        this.actorId = actorId;
        this.actorEmail = actorEmail;
        this.actorRoles = actorRoles;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.outcome = outcome;
        this.detail = detail;
        this.clientIp = clientIp;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getSource() {
        return source;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public String getActorRoles() {
        return actorRoles;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getDetail() {
        return detail;
    }

    public String getClientIp() {
        return clientIp;
    }
}
