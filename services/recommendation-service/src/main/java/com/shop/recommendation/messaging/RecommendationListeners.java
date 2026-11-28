package com.shop.recommendation.messaging;

import com.shop.common.event.Topics;
import com.shop.recommendation.service.LearningService;
import com.shop.support.kafka.EventReader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Học từ hành vi: danh mục, đơn đã thanh toán, lượt xem/tìm kiếm, giỏ hàng, đánh giá. */
@Component
public class RecommendationListeners {

    private final LearningService learning;
    private final EventReader reader;

    public RecommendationListeners(LearningService learning, EventReader reader) {
        this.learning = learning;
        this.reader = reader;
    }

    @KafkaListener(id = "recommendation-service.product-events", topics = Topics.PRODUCT_EVENTS)
    public void onProductEvent(String message) {
        learning.handleProductEvent(reader.read(message));
    }

    @KafkaListener(id = "recommendation-service.order-events", topics = Topics.ORDER_EVENTS)
    public void onOrderEvent(String message) {
        learning.handleOrderEvent(reader.read(message));
    }

    @KafkaListener(id = "recommendation-service.user-activity-events", topics = Topics.USER_ACTIVITY_EVENTS)
    public void onActivityEvent(String message) {
        learning.handleActivityEvent(reader.read(message));
    }

    @KafkaListener(id = "recommendation-service.cart-events", topics = Topics.CART_EVENTS)
    public void onCartEvent(String message) {
        learning.handleCartEvent(reader.read(message));
    }

    @KafkaListener(id = "recommendation-service.review-events", topics = Topics.REVIEW_EVENTS)
    public void onReviewEvent(String message) {
        learning.handleReviewEvent(reader.read(message));
    }
}
