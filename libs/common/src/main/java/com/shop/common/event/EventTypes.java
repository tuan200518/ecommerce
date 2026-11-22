package com.shop.common.event;

/** Danh sách eventType chuẩn hóa. */
public final class EventTypes {

    // user-events
    public static final String USER_REGISTERED = "UserRegistered";
    public static final String USER_LOGGED_IN = "UserLoggedIn";
    public static final String USER_ROLES_CHANGED = "UserRolesChanged";

    // product-events
    public static final String PRODUCT_CREATED = "ProductCreated";
    public static final String PRODUCT_UPDATED = "ProductUpdated";
    public static final String PRODUCT_DISCONTINUED = "ProductDiscontinued";

    // cart-events
    public static final String CART_ITEM_ADDED = "CartItemAdded";
    public static final String CART_ITEM_UPDATED = "CartItemUpdated";
    public static final String CART_ITEM_REMOVED = "CartItemRemoved";
    public static final String CART_CLEARED = "CartCleared";

    // order-events
    public static final String ORDER_CREATED = "OrderCreated";
    public static final String PAYMENT_REQUESTED = "PaymentRequested";
    public static final String ORDER_CONFIRMED = "OrderConfirmed";
    public static final String ORDER_CANCELLED = "OrderCancelled";
    public static final String ORDER_STATUS_CHANGED = "OrderStatusChanged";

    // inventory-events
    public static final String INVENTORY_RESERVED = "InventoryReserved";
    public static final String INVENTORY_REJECTED = "InventoryRejected";
    public static final String INVENTORY_COMMITTED = "InventoryCommitted";
    public static final String INVENTORY_RELEASED = "InventoryReleased";
    public static final String STOCK_UPDATED = "StockUpdated";
    public static final String STOCK_LOW = "StockLow";

    // payment-events
    public static final String PAYMENT_COMPLETED = "PaymentCompleted";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String PAYMENT_REFUNDED = "PaymentRefunded";

    // notification-events
    public static final String NOTIFICATION_REQUESTED = "NotificationRequested";

    // analytics-events
    public static final String METRIC_UPDATED = "MetricUpdated";
    public static final String ALERT_RAISED = "AlertRaised";

    private EventTypes() {
    }
}
