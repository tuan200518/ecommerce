package com.shop.order.messaging;

import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.payload.InventoryEvents.InventoryRejected;
import com.shop.common.event.payload.InventoryEvents.InventoryReserved;
import com.shop.common.event.payload.PaymentEvents.PaymentResult;
import com.shop.common.event.payload.ProductEvents.ProductChanged;
import com.shop.order.domain.ProductSnapshot;
import com.shop.order.domain.ProductSnapshotRepository;
import com.shop.order.service.OrderService;
import com.shop.support.kafka.EventReader;
import com.shop.support.outbox.IdempotencyGuard;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
