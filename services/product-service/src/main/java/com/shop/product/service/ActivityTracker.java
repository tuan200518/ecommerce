package com.shop.product.service;

import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.ActivityEvents.ProductViewed;
import com.shop.common.event.payload.ActivityEvents.SearchPerformed;
import com.shop.product.web.ProductDtos.ProductDto;
import com.shop.support.outbox.OutboxService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi nhận hành vi người dùng lên topic user-activity-events (qua Outbox như mọi sự kiện khác).
 * Đây là dữ liệu "best effort": phía gọi (ProductController) bỏ qua lỗi ghi nhận để không làm hỏng request xem/tìm sản phẩm của khách.
 */
@Component
public class ActivityTracker {

    private final OutboxService outbox;

    public ActivityTracker(OutboxService outbox) {
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void productViewed(Long userId, ProductDto p) {
        outbox.publish(Topics.USER_ACTIVITY_EVENTS, EventTypes.PRODUCT_VIEWED, String.valueOf(p.id()),
                new ProductViewed(userId, p.id(), p.name(), p.categoryId(), p.price()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void searchPerformed(Long userId, String query, Long categoryId, long resultCount) {
        outbox.publish(Topics.USER_ACTIVITY_EVENTS, EventTypes.SEARCH_PERFORMED,
                userId == null ? "anonymous" : String.valueOf(userId),
                new SearchPerformed(userId, query.trim(), categoryId, resultCount));
    }
}
