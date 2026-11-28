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

    // user-activity-events
    public static final String PRODUCT_VIEWED = "ProductViewed";
    public static final String SEARCH_PERFORMED = "SearchPerformed";

    // fraud-alert-events
    /** Kết quả chấm điểm rủi ro cho MỌI đơn mới (Order Service chờ sự kiện này trước khi yêu cầu thanh toán). */
    public static final String RISK_ASSESSED = "RiskAssessed";
    /** Đơn vượt ngưỡng rủi ro, cần nhân viên xác minh (dùng cho cảnh báo / thông báo). */
    public static final String ORDER_FLAGGED = "OrderFlagged";
    /** Nhân viên đã xác minh: chấp nhận hoặc từ chối đơn. */
    public static final String RISK_REVIEWED = "RiskReviewed";

    // shipping-events
    public static final String SHIPMENT_CREATED = "ShipmentCreated";
    public static final String SHIPMENT_STATUS_CHANGED = "ShipmentStatusChanged";
    public static final String SHIPMENT_DELIVERED = "ShipmentDelivered";

    // loyalty-events
    public static final String POINTS_EARNED = "PointsEarned";
    public static final String POINTS_REVERSED = "PointsReversed";
    public static final String TIER_CHANGED = "TierChanged";
    /** Khách đổi điểm lấy phần thưởng (Promotion Service phát voucher tương ứng). */
    public static final String POINTS_REDEEMED = "PointsRedeemed";

    // review-events
    public static final String REVIEW_SUBMITTED = "ReviewSubmitted";
    public static final String REVIEW_VISIBILITY_CHANGED = "ReviewVisibilityChanged";

    // promotion-events
    /** Kết quả định giá khuyến mãi cho MỌI đơn mới (có thể giảm 0đ) – một điều kiện để yêu cầu thanh toán. */
    public static final String PROMOTION_APPLIED = "PromotionApplied";
    /** Mã giảm giá không hợp lệ / hết suất flash sale → Order Service hủy đơn. */
    public static final String PROMOTION_REJECTED = "PromotionRejected";
    public static final String VOUCHER_ISSUED = "VoucherIssued";
    /** Đơn bị hủy → hoàn suất / lượt ưu đãi; Analytics trừ lại khoản giảm giá đã tính. */
    public static final String PROMOTION_RELEASED = "PromotionReleased";

    // support-events
    public static final String TICKET_CREATED = "TicketCreated";
    public static final String TICKET_STATUS_CHANGED = "TicketStatusChanged";
    public static final String TICKET_MESSAGE_POSTED = "TicketMessagePosted";

    // audit-events
    public static final String AUDIT_RECORDED = "AuditRecorded";

    private EventTypes() {
    }
}
