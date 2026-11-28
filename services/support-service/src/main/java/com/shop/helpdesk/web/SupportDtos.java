package com.shop.helpdesk.web;

import com.shop.helpdesk.domain.OrderRef;
import com.shop.helpdesk.domain.Ticket;
import com.shop.helpdesk.domain.TicketMessage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class SupportDtos {

    private SupportDtos() {
    }

    public record CreateTicketRequest(
            @NotBlank(message = "Vui lòng nhập tiêu đề") @Size(max = 150) String subject,
            @NotBlank @Pattern(regexp = "ORDER|PAYMENT|SHIPPING|PRODUCT|PROMOTION|ACCOUNT|OTHER",
                    message = "Chủ đề không hợp lệ") String category,
            Long orderId,
            @NotBlank(message = "Vui lòng mô tả vấn đề") @Size(max = 2000) String message) {
    }

    public record MessageRequest(@NotBlank(message = "Tin nhắn trống") @Size(max = 2000) String content) {
    }

    public record RateRequest(@Min(1) @Max(5) int rating, @Size(max = 500) String comment) {
    }

    /**
     * Nhân viên cập nhật yêu cầu. Trường null = giữ nguyên.
     *
     * @param assignee "ME" (nhận xử lý) | "NONE" (bỏ phân công)
     */
    public record StaffUpdateRequest(
            @Pattern(regexp = "IN_PROGRESS|WAITING_CUSTOMER|RESOLVED|CLOSED", message = "Trạng thái không hợp lệ") String status,
            @Pattern(regexp = "LOW|NORMAL|HIGH|URGENT", message = "Mức ưu tiên không hợp lệ") String priority,
            @Pattern(regexp = "ME|NONE", message = "Phân công không hợp lệ") String assignee,
            @Size(max = 500) String note) {
    }

    /** @param unread số tin nhắn chưa đọc theo góc nhìn người xem (khách hoặc nhân viên) */
    public record TicketDto(long id, String code, long userId, String customerEmail, String subject, String category,
                            Long orderId, String status, String priority, String assigneeEmail,
                            Instant firstResponseDueAt, Instant firstResponseAt, boolean overdue, Instant resolvedAt,
                            Instant closedAt, Instant lastMessageAt, int unread, Integer rating, String ratingComment,
                            Instant createdAt, Instant updatedAt) {

        public static TicketDto from(Ticket t, boolean staffView) {
            return new TicketDto(t.getId(), t.code(), t.getUserId(), t.getCustomerEmail(), t.getSubject(),
                    t.getCategory().name(), t.getOrderId(), t.getStatus().name(), t.getPriority().name(),
                    t.getAssigneeEmail(), t.getFirstResponseDueAt(), t.getFirstResponseAt(),
                    t.isOverdue(Instant.now()), t.getResolvedAt(), t.getClosedAt(), t.getLastMessageAt(),
                    staffView ? t.getStaffUnread() : t.getCustomerUnread(), t.getRating(), t.getRatingComment(),
                    t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    public record MessageDto(long id, long ticketId, String authorName, String kind, String content,
                             Instant createdAt) {
        public static MessageDto from(TicketMessage m) {
            return new MessageDto(m.getId(), m.getTicketId(), m.getAuthorName(), m.getKind().name(), m.getContent(),
                    m.getCreatedAt());
        }
    }

    public record OrderInfoDto(long orderId, String status, BigDecimal totalAmount) {
        public static OrderInfoDto from(OrderRef o) {
            return o == null ? null : new OrderInfoDto(o.getOrderId(), o.getStatus(), o.getTotalAmount());
        }
    }

    public record TicketDetailDto(TicketDto ticket, List<MessageDto> messages, OrderInfoDto order) {
    }

    /**
     * @param avgFirstResponseMinutes thời gian phản hồi đầu tiên trung bình (7 ngày)
     */
    public record SummaryDto(Map<String, Long> byStatus, long active, long unassigned, long overdue,
                             long resolvedToday, Double avgFirstResponseMinutes, Double avgRating, long ratingCount) {
    }
}
