package com.shop.common.event;

import java.util.List;

/** Tên các Kafka topic dùng trong hệ thống (mục 3.1 của planning). */
public final class Topics {

    public static final String USER_EVENTS = "user-events";
    public static final String PRODUCT_EVENTS = "product-events";
    public static final String CART_EVENTS = "cart-events";
    public static final String ORDER_EVENTS = "order-events";
    public static final String PAYMENT_EVENTS = "payment-events";
    public static final String INVENTORY_EVENTS = "inventory-events";
    public static final String NOTIFICATION_EVENTS = "notification-events";
    public static final String ANALYTICS_EVENTS = "analytics-events";

    /** Hành vi người dùng: xem sản phẩm, tìm kiếm (producer: product-service). */
    public static final String USER_ACTIVITY_EVENTS = "user-activity-events";
    /** Kết quả chấm điểm rủi ro và quyết định xác minh thủ công (producer: fraud-service). */
    public static final String FRAUD_ALERT_EVENTS = "fraud-alert-events";
    /** Vòng đời vận đơn (producer: shipping-service). */
    public static final String SHIPPING_EVENTS = "shipping-events";
    /** Biến động điểm thưởng / hạng thành viên (producer: loyalty-service). */
    public static final String LOYALTY_EVENTS = "loyalty-events";
    /** Đánh giá sản phẩm (producer: review-service). */
    public static final String REVIEW_EVENTS = "review-events";
    /** Kết quả áp dụng khuyến mãi cho đơn, voucher được phát (producer: promotion-service). */
    public static final String PROMOTION_EVENTS = "promotion-events";
    /** Nhật ký kiểm toán thao tác quản trị / bảo mật (producer: mọi service, consumer: audit-service). */
    public static final String AUDIT_EVENTS = "audit-events";

    /** Hậu tố topic Dead Letter (mặc định của Spring Kafka DeadLetterPublishingRecoverer). */
    public static final String DLT_SUFFIX = "-dlt";

    public static final List<String> ALL = List.of(
            USER_EVENTS, PRODUCT_EVENTS, CART_EVENTS, ORDER_EVENTS,
            PAYMENT_EVENTS, INVENTORY_EVENTS, NOTIFICATION_EVENTS, ANALYTICS_EVENTS,
            USER_ACTIVITY_EVENTS, FRAUD_ALERT_EVENTS, SHIPPING_EVENTS, LOYALTY_EVENTS, REVIEW_EVENTS,
            PROMOTION_EVENTS, AUDIT_EVENTS);

    private Topics() {
    }
}
