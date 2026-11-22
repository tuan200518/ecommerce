package com.shop.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.payload.OrderEvents.OrderCreated;
import com.shop.common.event.payload.OrderEvents.OrderLine;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.event.payload.ProductEvents.ProductChanged;
import com.shop.inventory.domain.InventoryRepository;
import com.shop.inventory.domain.ReservationRepository;
import com.shop.inventory.service.InsufficientStockException;
import com.shop.inventory.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kịch bản B (Flash Sale) + TC04 + TC09, dùng chung cho H2 và PostgreSQL (Testcontainers).
 */
abstract class FlashSaleScenario {

    @Autowired
    InventoryService service;
    @Autowired
    InventoryRepository inventories;
    @Autowired
    ReservationRepository reservations;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    private EventEnvelope productCreated(long productId, int stock) {
        return EventEnvelope.create(mapper, EventTypes.PRODUCT_CREATED, "test", String.valueOf(productId),
                new ProductChanged(productId, "SKU-" + productId, "Flash item " + productId, new BigDecimal("99000"),
                        1L, "Sale", null, true, stock));
    }

    private OrderCreated order(long orderId, long productId, int qty) {
        return new OrderCreated(orderId, 1000 + orderId,
                List.of(new OrderLine(productId, "Flash item", qty, new BigDecimal("99000"))),
                new BigDecimal("99000").multiply(BigDecimal.valueOf(qty)), "MOCK_CARD", Instant.now());
    }

    private EventEnvelope wrap(String type, long orderId, Object payload) {
        return EventEnvelope.create(mapper, type, "test", String.valueOf(orderId), payload);
    }

    @Test
    void flashSale_neverOversells() throws Exception {
        long productId = 900;
        EventEnvelope created = productCreated(productId, 10);
        service.applyProductEvent(created, mapper.treeToValue(created.payload(), ProductChanged.class));

        int buyers = 50;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i <= buyers; i++) {
            long orderId = 10_000 + i;
            futures.add(pool.submit(() -> {
                start.await();
                OrderCreated oc = order(orderId, productId, 1);
                EventEnvelope env = wrap(EventTypes.ORDER_CREATED, orderId, oc);
                try {
                    service.reserve(env, oc);
                    accepted.incrementAndGet();
                } catch (InsufficientStockException e) {
                    service.reject(env, oc, e);
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(accepted.get()).isEqualTo(10);
        assertThat(rejected.get()).isEqualTo(40);
        var inv = inventories.findById(productId).orElseThrow();
        assertThat(inv.getAvailable()).isZero();
        assertThat(inv.getReserved()).isEqualTo(10);
        Integer rejectedEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = ?", Integer.class, EventTypes.INVENTORY_REJECTED);
        assertThat(rejectedEvents).isGreaterThanOrEqualTo(40);
    }

    @Test
    void cancelReleasesStock_confirmCommits_andDuplicatesAreIgnored() throws Exception {
        long productId = 901;
        EventEnvelope created = productCreated(productId, 5);
        service.applyProductEvent(created, mapper.treeToValue(created.payload(), ProductChanged.class));

        OrderCreated o1 = order(1, productId, 2);
        EventEnvelope e1 = wrap(EventTypes.ORDER_CREATED, 1, o1);
        service.reserve(e1, o1);
        service.reserve(e1, o1); // TC09: sự kiện trùng → bỏ qua
        assertThat(inventories.findById(productId).orElseThrow().getAvailable()).isEqualTo(3);

        OrderCreated o2 = order(2, productId, 3);
        service.reserve(wrap(EventTypes.ORDER_CREATED, 2, o2), o2);
        assertThat(inventories.findById(productId).orElseThrow().getAvailable()).isZero();

        // Đơn 1 thanh toán thành công → xuất kho
        service.commit(wrap(EventTypes.ORDER_CONFIRMED, 1, status(1)), 1);
        // Đơn 2 thanh toán thất bại → hoàn kho (compensating action)
        EventEnvelope cancel2 = wrap(EventTypes.ORDER_CANCELLED, 2, status(2));
        service.release(cancel2, 2);
        service.release(cancel2, 2); // trùng → không hoàn kho 2 lần

        var inv = inventories.findById(productId).orElseThrow();
        assertThat(inv.getAvailable()).isEqualTo(3);
        assertThat(inv.getReserved()).isZero();
        assertThat(reservations.findByOrderIdOrderByProductIdAsc(1)).allMatch(r -> r.getStatus().name().equals("COMMITTED"));
        assertThat(reservations.findByOrderIdOrderByProductIdAsc(2)).allMatch(r -> r.getStatus().name().equals("RELEASED"));
    }

    private static OrderStatusChanged status(long orderId) {
        return new OrderStatusChanged(orderId, 1, "AWAITING_PAYMENT", "X", BigDecimal.ONE, null, List.of());
    }
}
