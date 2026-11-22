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

    /** Hậu tố topic Dead Letter (mặc định của Spring Kafka DeadLetterPublishingRecoverer). */
    public static final String DLT_SUFFIX = "-dlt";

    public static final List<String> ALL = List.of(
            USER_EVENTS, PRODUCT_EVENTS, CART_EVENTS, ORDER_EVENTS,
            PAYMENT_EVENTS, INVENTORY_EVENTS, NOTIFICATION_EVENTS, ANALYTICS_EVENTS);

    private Topics() {
    }
}
