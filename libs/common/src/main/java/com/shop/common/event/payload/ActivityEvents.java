package com.shop.common.event.payload;

import java.math.BigDecimal;

/** Payload cho topic user-activity-events (hành vi người dùng, phục vụ phễu chuyển đổi và gợi ý sau này). */
public final class ActivityEvents {

    /** @param userId null nếu khách chưa đăng nhập */
    public record ProductViewed(Long userId, long productId, String productName, Long categoryId, BigDecimal price) {
    }

    public record SearchPerformed(Long userId, String query, Long categoryId, long resultCount) {
    }

    private ActivityEvents() {
    }
}
