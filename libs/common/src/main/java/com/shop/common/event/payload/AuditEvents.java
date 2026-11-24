package com.shop.common.event.payload;

/** Payload cho topic audit-events. */
public final class AuditEvents {

    /**
     * Một dòng nhật ký kiểm toán: AI đã làm GÌ, trên ĐỐI TƯỢNG nào, KẾT QUẢ ra sao.
     * Service ghi sự kiện này qua outbox trong cùng transaction với thao tác → có nhật ký khi và chỉ khi thao tác
     * đã được commit (riêng các lần thất bại như đăng nhập sai được ghi trong transaction riêng).
     *
     * @param actorId      null nếu chưa xác định được người dùng (vd. đăng nhập sai email)
     * @param action       vd. LOGIN_FAILED, PRODUCT_UPDATED, VOUCHER_CREATED
     * @param resourceType vd. USER, PRODUCT, ORDER, VOUCHER
     * @param outcome      SUCCESS | FAILURE
     * @param clientIp     IP client do API Gateway xác định (null nếu không qua Gateway)
     */
    public record AuditRecorded(Long actorId, String actorEmail, String actorRoles, String action,
                                String resourceType, String resourceId, String outcome, String detail,
                                String clientIp) {
    }

    private AuditEvents() {
    }
}
