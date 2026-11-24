package com.shop.common.event.payload;

/** Payload cho topic review-events (ReviewSubmitted / ReviewVisibilityChanged). */
public final class ReviewEvents {

    public record ReviewChanged(long reviewId, long productId, long userId, int rating, boolean visible,
                                boolean withImages) {
    }

    private ReviewEvents() {
    }
}
