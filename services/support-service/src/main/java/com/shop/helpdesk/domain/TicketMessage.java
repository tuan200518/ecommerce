package com.shop.helpdesk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "ticket_messages", indexes = @Index(name = "idx_ticket_message", columnList = "ticketId,id"))
public class TicketMessage {

    public enum Kind {
        CUSTOMER, STAFF,
        /** Tin nhắn tự động: tạo yêu cầu, đổi trạng thái, đơn hàng liên quan thay đổi... */
        SYSTEM
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long ticketId;

    private Long authorId;

    @Column(nullable = false, length = 150)
    private String authorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    @Column(nullable = false, length = 2000)
    private String content;

    @Column(nullable = false)
    private Instant createdAt;

    protected TicketMessage() {
    }

    public TicketMessage(long ticketId, Long authorId, String authorName, Kind kind, String content) {
        this.ticketId = ticketId;
        this.authorId = authorId;
        this.authorName = authorName;
        this.kind = kind;
        this.content = content;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public Kind getKind() {
        return kind;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
