package com.shop.order.messaging;

import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.payload.FraudEvents.RiskAssessed;
import com.shop.common.event.payload.FraudEvents.RiskReviewed;
import com.shop.common.event.payload.FraudEvents.RiskSignal;
import com.shop.common.event.payload.InventoryEvents.InventoryRejected;
import com.shop.common.event.payload.InventoryEvents.InventoryReserved;
import com.shop.common.event.payload.PaymentEvents.PaymentResult;
import com.shop.common.event.payload.ProductEvents.ProductChanged;
import com.shop.common.event.payload.PromotionEvents.PromotionApplied;
import com.shop.common.event.payload.PromotionEvents.PromotionRejected;
import com.shop.common.event.payload.ShippingEvents.ShipmentChanged;
import com.shop.order.domain.ProductSnapshot;
import com.shop.order.domain.ProductSnapshotRepository;
import com.shop.order.service.OrderService;
import com.shop.support.kafka.EventReader;
import com.shop.support.outbox.IdempotencyGuard;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bộ điều phối Saga phía Order Service: nhận kết quả từ Inventory / Payment và cập nhật đơn.
 * Mỗi sự kiện được xử lý trong một transaction, kèm dấu idempotency → xử lý đúng một lần về mặt nghiệp vụ.
 */
@Component
public class OrderSagaHandler {

    static final String CONSUMER = "order-service";

    private final OrderService orderService;
    private final ProductSnapshotRepository products;
    private final IdempotencyGuard guard;
    private final EventReader reader;

    public OrderSagaHandler(OrderService orderService, ProductSnapshotRepository products,
                            IdempotencyGuard guard, EventReader reader) {
        this.orderService = orderService;
        this.products = products;
        this.guard = guard;
        this.reader = reader;
    }

    @Transactional
    public void handleInventoryEvent(EventEnvelope env) {
        switch (env.eventType()) {
            case EventTypes.INVENTORY_RESERVED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    orderService.onInventoryReserved(reader.payload(env, InventoryReserved.class).orderId());
                }
            }
            case EventTypes.INVENTORY_REJECTED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    InventoryRejected p = reader.payload(env, InventoryRejected.class);
                    orderService.onInventoryRejected(p.orderId(), p.reason());
                }
            }
            default -> {
                // StockUpdated, StockLow... không liên quan tới Order Service
            }
        }
    }

    @Transactional
    public void handlePaymentEvent(EventEnvelope env) {
        switch (env.eventType()) {
            case EventTypes.PAYMENT_COMPLETED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    orderService.onPaymentCompleted(reader.payload(env, PaymentResult.class).orderId());
                }
            }
            case EventTypes.PAYMENT_FAILED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    PaymentResult p = reader.payload(env, PaymentResult.class);
                    orderService.onPaymentFailed(p.orderId(), p.reason());
                }
            }
            default -> {
            }
        }
    }

    /** Kết quả chấm điểm rủi ro / quyết định xác minh từ Fraud Service. */
    @Transactional
    public void handleFraudEvent(EventEnvelope env) {
        switch (env.eventType()) {
            case EventTypes.RISK_ASSESSED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    RiskAssessed p = reader.payload(env, RiskAssessed.class);
                    String summary = p.signals() == null ? "" : p.signals().stream()
                            .map(RiskSignal::detail).collect(Collectors.joining("; "));
                    orderService.onRiskAssessed(p.orderId(), p.score(), p.level(), p.requiresReview(), summary);
                }
            }
            case EventTypes.RISK_REVIEWED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    RiskReviewed p = reader.payload(env, RiskReviewed.class);
                    orderService.onRiskReviewed(p.orderId(), p.approved(), p.reviewer(), p.note());
                }
            }
            default -> {
                // OrderFlagged: chỉ phục vụ cảnh báo, Order Service đã xử lý qua RiskAssessed
            }
        }
    }

    /** Kết quả định giá khuyến mãi từ Promotion Service. */
    @Transactional
    public void handlePromotionEvent(EventEnvelope env) {
        switch (env.eventType()) {
            case EventTypes.PROMOTION_APPLIED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    PromotionApplied p = reader.payload(env, PromotionApplied.class);
                    Map<Long, BigDecimal> salePrices = new HashMap<>();
                    if (p.lines() != null) {
                        p.lines().stream().filter(l -> l.campaignId() != null && l.salePrice() != null)
                                .forEach(l -> salePrices.put(l.productId(), l.salePrice()));
                    }
                    orderService.onPromotionApplied(p.orderId(), salePrices, p.itemDiscount(), p.voucherDiscount(),
                            p.voucherCode(), p.summary());
                }
            }
            case EventTypes.PROMOTION_REJECTED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    PromotionRejected p = reader.payload(env, PromotionRejected.class);
                    orderService.onPromotionRejected(p.orderId(), p.reason());
                }
            }
            default -> {
                // VoucherIssued: không liên quan tới đơn hàng
            }
        }
    }

    /** Vận đơn đổi trạng thái → cập nhật SHIPPING / DELIVERED. */
    @Transactional
    public void handleShippingEvent(EventEnvelope env) {
        if (env.is(EventTypes.SHIPMENT_STATUS_CHANGED) || env.is(EventTypes.SHIPMENT_DELIVERED)) {
            if (guard.firstTime(env, CONSUMER)) {
                ShipmentChanged p = reader.payload(env, ShipmentChanged.class);
                orderService.onShipmentStatus(p.orderId(), p.status(), p.carrier(), p.trackingNumber());
            }
        }
    }

    /** Duy trì read-model sản phẩm (upsert theo productId nên tự nhiên idempotent). */
    @Transactional
    public void handleProductEvent(EventEnvelope env) {
        ProductChanged p = reader.payload(env, ProductChanged.class);
        ProductSnapshot snapshot = products.findById(p.productId()).orElseGet(() -> new ProductSnapshot(p.productId()));
        boolean active = p.active() && !EventTypes.PRODUCT_DISCONTINUED.equals(env.eventType());
        snapshot.update(p.name(), p.price(), active);
        products.save(snapshot);
    }
}
