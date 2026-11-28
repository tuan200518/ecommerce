package com.shop.common.event.payload;

/** Payload cho topic support-events. */
public final class SupportEvents {

    /**
     * TicketCreated / TicketStatusChanged.
     *
     * @param previousStatus null với TicketCreated
     * @param category       ORDER | PAYMENT | SHIPPING | PRODUCT | PROMOTION | ACCOUNT | OTHER
     */
    public record TicketChanged(long ticketId, String code, long userId, Long orderId, String category,
                                String priority, String previousStatus, String status, String assigneeEmail) {
    }

    /** Tin nhắn mới trong yêu cầu hỗ trợ (chỉ gửi bản xem trước, không gửi toàn văn). */
    public record TicketMessagePosted(long ticketId, String code, long userId, long authorId, boolean staff,
                                      String preview) {
    }

    private SupportEvents() {
    }
}
