package com.shop.common.event.payload;

/** Payload cho topic notification-events. */
public final class NotificationEvents {

    /**
     * Yêu cầu gửi thông báo. Gửi tới một người dùng cụ thể (userId) hoặc tới mọi người
     * thuộc một vai trò (targetRole), ví dụ WAREHOUSE_MANAGER khi tồn kho thấp.
     */
    public record NotificationRequested(Long userId, String targetRole, String type,
                                        String title, String message, String referenceId) {
    }

    private NotificationEvents() {
    }
}
