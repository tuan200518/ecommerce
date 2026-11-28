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
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/** Yêu cầu hỗ trợ. Mã hiển thị "HT-<id>". */
@Entity
@Table(name = "tickets", indexes = {
        @Index(name = "idx_ticket_user", columnList = "userId,updatedAt"),
        @Index(name = "idx_ticket_status", columnList = "status,updatedAt"),
        @Index(name = "idx_ticket_order", columnList = "orderId")
})
public class Ticket {

    public enum Status {
        /** Mới / khách vừa phản hồi, chờ nhân viên. */
        OPEN,
        IN_PROGRESS,
        /** Nhân viên đã trả lời, chờ khách bổ sung thông tin. */
        WAITING_CUSTOMER,
        RESOLVED,
        CLOSED;

        public boolean isActive() {
            return this != RESOLVED && this != CLOSED;
        }
    }

    public enum Priority {
        LOW, NORMAL, HIGH, URGENT
    }

    public enum Category {
        ORDER, PAYMENT, SHIPPING, PRODUCT, PROMOTION, ACCOUNT, OTHER
    }

    /** Trạng thái nhân viên được đặt trực tiếp. */
    public static final Set<Status> STAFF_SETTABLE = EnumSet.of(Status.IN_PROGRESS, Status.WAITING_CUSTOMER,
            Status.RESOLVED, Status.CLOSED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 255)
    private String customerEmail;

    @Column(nullable = false, length = 150)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Category category;

    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Priority priority;

    private Long assigneeId;

    @Column(length = 255)
    private String assigneeEmail;

    /** Hạn phản hồi đầu tiên theo SLA. */
    @Column(nullable = false)
    private Instant firstResponseDueAt;

    private Instant firstResponseAt;

    /** Đã cảnh báo trễ SLA (chỉ cảnh báo một lần). */
    private boolean slaBreachNotified;

    private Instant resolvedAt;

    private Instant closedAt;

    @Column(nullable = false)
    private Instant lastMessageAt;

    /** Tin nhắn chưa đọc của khách / của nhân viên. */
    private int customerUnread;

    private int staffUnread;

    private Integer rating;

    @Column(length = 500)
    private String ratingComment;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Ticket() {
    }

    public Ticket(long userId, String customerEmail, String subject, Category category, Long orderId,
                  Priority priority, Instant firstResponseDueAt) {
        this.userId = userId;
        this.customerEmail = customerEmail;
        this.subject = subject;
        this.category = category;
        this.orderId = orderId;
        this.priority = priority;
        this.firstResponseDueAt = firstResponseDueAt;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        this.lastMessageAt = createdAt;
    }

    public String code() {
        return "HT-" + id;
    }

    /** @return trạng thái cũ */
    public Status changeStatus(Status next) {
        Status previous = status;
        status = next;
        Instant now = Instant.now();
        updatedAt = now;
        if (next == Status.RESOLVED) {
            resolvedAt = now;
        } else if (next == Status.CLOSED) {
            closedAt = now;
        } else {
            resolvedAt = null;
            closedAt = null;
        }
        return previous;
    }

    public void staffReplied(long staffId, String staffEmail) {
        Instant now = Instant.now();
        if (firstResponseAt == null) {
            firstResponseAt = now;
        }
        if (assigneeId == null) {
            assign(staffId, staffEmail);
        }
        customerUnread++;
        lastMessageAt = now;
        updatedAt = now;
    }

    public void customerReplied() {
        staffUnread++;
        lastMessageAt = Instant.now();
        updatedAt = lastMessageAt;
    }

    public void assign(Long staffId, String staffEmail) {
        this.assigneeId = staffId;
        this.assigneeEmail = staffEmail;
        this.updatedAt = Instant.now();
    }

    public void changePriority(Priority priority, Instant newDueAt) {
        this.priority = priority;
        if (firstResponseAt == null && newDueAt != null) {
            this.firstResponseDueAt = newDueAt;
            this.slaBreachNotified = false;
        }
        this.updatedAt = Instant.now();
    }

    public boolean isOverdue(Instant now) {
        return status.isActive() && firstResponseAt == null && now.isAfter(firstResponseDueAt);
    }

    public void markSlaBreachNotified() {
        this.slaBreachNotified = true;
    }

    public void readByCustomer() {
        customerUnread = 0;
    }

    public void readByStaff() {
        staffUnread = 0;
    }

    public void rate(int rating, String comment) {
        this.rating = rating;
        this.ratingComment = comment;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public String getSubject() {
        return subject;
    }

    public Category getCategory() {
        return category;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Status getStatus() {
        return status;
    }

    public Priority getPriority() {
        return priority;
    }

    public Long getAssigneeId() {
        return assigneeId;
    }

    public String getAssigneeEmail() {
        return assigneeEmail;
    }

    public Instant getFirstResponseDueAt() {
        return firstResponseDueAt;
    }

    public Instant getFirstResponseAt() {
        return firstResponseAt;
    }

    public boolean isSlaBreachNotified() {
        return slaBreachNotified;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public int getCustomerUnread() {
        return customerUnread;
    }

    public int getStaffUnread() {
        return staffUnread;
    }

    public Integer getRating() {
        return rating;
    }

    public String getRatingComment() {
        return ratingComment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
