package com.shop.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.InventoryEvents.InventoryRejected;
import com.shop.common.event.payload.InventoryEvents.InventoryReserved;
import com.shop.common.event.payload.InventoryEvents.ReservedLine;
import com.shop.common.event.payload.PaymentEvents.PaymentResult;
import com.shop.common.event.payload.ProductEvents.ProductChanged;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.order.domain.OrderRepository;
import com.shop.order.domain.OrderStatusHistoryRepository;
import com.shop.order.service.OrderService;
import com.shop.order.web.OrderDtos.CreateOrderRequest;
import com.shop.order.web.OrderDtos.ItemRequest;
import com.shop.order.web.OrderDtos.OrderDto;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kiểm thử luồng Saga của Order Service với Kafka nhúng + H2:
 * TC01 (OrderCreated được publish), TC02 (thanh toán thành công), TC03 (thanh toán thất bại),
 * TC04 (hết hàng → hủy), TC09 (gửi lại cùng sự kiện không gây tác động trùng).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:orderdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "shop.kafka.partitions=1",
        "shop.outbox.poll-interval-ms=100",
        "logging.level.org.apache.kafka=WARN",
        "logging.level.kafka=WARN",
        "logging.level.state.change.logger=WARN"
})
@EmbeddedKafka(partitions = 1, kraft = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderSagaIntegrationTest {

    private static final AuthenticatedUser CUSTOMER = new AuthenticatedUser(42L, "c@shop.local", Set.of(Roles.CUSTOMER));

    @Autowired
    EmbeddedKafkaBroker broker;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    OrderService orderService;
    @Autowired
    OrderRepository orders;
    @Autowired
    OrderStatusHistoryRepository history;

    private Consumer<String, String> consumer;
    private final List<EventEnvelope> orderEvents = new CopyOnWriteArrayList<>();
    private Thread pollingThread;
    private volatile boolean polling = true;

    @BeforeAll
    void setUp() throws Exception {
        Map<String, Object> props = KafkaTestUtils.consumerProps("saga-test", "true", broker);
        props.put("auto.offset.reset", "earliest");
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        consumer.subscribe(List.of(Topics.ORDER_EVENTS));
        pollingThread = new Thread(() -> {
            while (polling) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
                    try {
                        orderEvents.add(mapper.readValue(r.value(), EventEnvelope.class));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            consumer.close();
        });
        pollingThread.start();

        // Product Service phát ProductCreated → Order Service xây read-model sản phẩm
        send(Topics.PRODUCT_EVENTS, EventTypes.PRODUCT_CREATED, "1",
                new ProductChanged(1L, "SKU-1", "Bàn phím cơ", new BigDecimal("1500000"), 1L, "Phụ kiện", null, true, 50));
        send(Topics.PRODUCT_EVENTS, EventTypes.PRODUCT_CREATED, "2",
                new ProductChanged(2L, "SKU-2", "Chuột không dây", new BigDecimal("350000"), 1L, "Phụ kiện", null, true, 50));
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            try {
                orderService.createOrder(CUSTOMER, request(1L, 1)); // thử đặt để chắc read-model đã có
                return true;
            } catch (Exception e) {
                return false;
            }
        });
    }

    @AfterAll
    void tearDown() throws Exception {
        polling = false;
        pollingThread.join(5000);
    }

    @Test
    void happyPath_orderConfirmedAfterReservationAndPayment_andDuplicateEventIgnored() {
        OrderDto order = orderService.createOrder(CUSTOMER, new CreateOrderRequest(
                List.of(new ItemRequest(1L, 2), new ItemRequest(2L, 1), new ItemRequest(2L, 1)),
                "1 Lê Lợi, Q1, TP.HCM", "MOCK_CARD"));
        assertThat(order.status()).isEqualTo("PENDING");
        assertThat(order.totalAmount()).isEqualByComparingTo("3700000");
        assertThat(order.items()).hasSize(2); // gộp các dòng cùng sản phẩm

        // TC01: OrderCreated được publish qua outbox
        awaitEvent(EventTypes.ORDER_CREATED, order.id());

        send(Topics.INVENTORY_EVENTS, EventTypes.INVENTORY_RESERVED, String.valueOf(order.id()),
                new InventoryReserved(order.id(), List.of(new ReservedLine(1L, 2), new ReservedLine(2L, 2))));
        awaitStatus(order.id(), "AWAITING_PAYMENT");
        awaitEvent(EventTypes.PAYMENT_REQUESTED, order.id());

        // TC02: thanh toán thành công → CONFIRMED
        EventEnvelope paid = send(Topics.PAYMENT_EVENTS, EventTypes.PAYMENT_COMPLETED, String.valueOf(order.id()),
                new PaymentResult(10L, order.id(), 42L, order.totalAmount(), "MOCK_CARD", "SUCCEEDED", null));
        awaitStatus(order.id(), "CONFIRMED");
        awaitEvent(EventTypes.ORDER_CONFIRMED, order.id());

        // TC09: gửi lại đúng sự kiện đó (cùng eventId) → không tác động thêm
        resend(Topics.PAYMENT_EVENTS, paid);
        sleep(1500);
        long confirmedTransitions = history.findByOrderIdOrderByIdAsc(order.id()).stream()
                .filter(h -> h.getToStatus().name().equals("CONFIRMED")).count();
        assertThat(confirmedTransitions).isEqualTo(1);
        assertThat(countEvents(EventTypes.ORDER_CONFIRMED, order.id())).isEqualTo(1);
    }

    @Test
    void paymentFailure_cancelsOrder_andPublishesCompensation() {
        OrderDto order = orderService.createOrder(CUSTOMER, request(2L, 3));
        send(Topics.INVENTORY_EVENTS, EventTypes.INVENTORY_RESERVED, String.valueOf(order.id()),
                new InventoryReserved(order.id(), List.of(new ReservedLine(2L, 3))));
        awaitStatus(order.id(), "AWAITING_PAYMENT");

        send(Topics.PAYMENT_EVENTS, EventTypes.PAYMENT_FAILED, String.valueOf(order.id()),
                new PaymentResult(11L, order.id(), 42L, order.totalAmount(), "MOCK_FAIL", "FAILED", "Thẻ bị từ chối"));
        awaitStatus(order.id(), "CANCELLED");
        // OrderCancelled là tín hiệu để Inventory Service hoàn kho (compensating action)
        awaitEvent(EventTypes.ORDER_CANCELLED, order.id());
    }

    @Test
    void outOfStock_cancelsOrder() {
        OrderDto order = orderService.createOrder(CUSTOMER, request(1L, 5));
        send(Topics.INVENTORY_EVENTS, EventTypes.INVENTORY_REJECTED, String.valueOf(order.id()),
                new InventoryRejected(order.id(), 1L, "Bàn phím cơ chỉ còn 2"));
        awaitStatus(order.id(), "CANCELLED");
        assertThat(orders.findById(order.id()).orElseThrow().getCancelReason()).contains("Không đủ tồn kho");
    }

    @Test
    void malformedMessage_doesNotBlockConsumer() {
        kafka.send(Topics.PAYMENT_EVENTS, "x", "{not-json");
        OrderDto order = orderService.createOrder(CUSTOMER, request(1L, 1));
        send(Topics.INVENTORY_EVENTS, EventTypes.INVENTORY_RESERVED, String.valueOf(order.id()),
                new InventoryReserved(order.id(), List.of(new ReservedLine(1L, 1))));
        awaitStatus(order.id(), "AWAITING_PAYMENT");
        send(Topics.PAYMENT_EVENTS, EventTypes.PAYMENT_COMPLETED, String.valueOf(order.id()),
                new PaymentResult(12L, order.id(), 42L, order.totalAmount(), "MOCK_CARD", "SUCCEEDED", null));
        awaitStatus(order.id(), "CONFIRMED");
    }

    // ------------------------------------------------------------------ helpers

    private static CreateOrderRequest request(long productId, int qty) {
        return new CreateOrderRequest(List.of(new ItemRequest(productId, qty)), "Địa chỉ test", "MOCK_CARD");
    }

    private EventEnvelope send(String topic, String type, String key, Object payload) {
        EventEnvelope env = EventEnvelope.create(mapper, type, "test", key, payload);
        resend(topic, env);
        return env;
    }

    private void resend(String topic, EventEnvelope env) {
        try {
            kafka.send(topic, env.aggregateId(), mapper.writeValueAsString(env)).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void awaitStatus(long orderId, String status) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(orders.findById(orderId).orElseThrow().getStatus().name()).isEqualTo(status));
    }

    private void awaitEvent(String type, long orderId) {
        await().atMost(Duration.ofSeconds(20)).until(() -> countEvents(type, orderId) > 0);
    }

    private long countEvents(String type, long orderId) {
        return orderEvents.stream()
                .filter(e -> e.is(type) && String.valueOf(orderId).equals(e.aggregateId()))
                .count();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
