package com.shop.analytics.streams;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.shop.analytics.model.AnalyticsModels.Alert;
import com.shop.analytics.model.AnalyticsModels.MetricUpdate;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.InventoryEvents.StockLow;
import com.shop.common.event.payload.OrderEvents.OrderCreated;
import com.shop.common.event.payload.OrderEvents.OrderLine;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.event.payload.OrderEvents.PaymentRequested;
import com.shop.common.event.payload.PaymentEvents.PaymentResult;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm thử topology bằng TopologyTestDriver (không cần broker):
 * TC05 — nhiều đơn liên tục → số liệu tổng hợp chính xác; kịch bản A — doanh thu theo cửa sổ.
 */
class AnalyticsTopologyTest {

    private static final long SPIKE_THRESHOLD = 3;
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private TopologyTestDriver driver;
    private TestInputTopic<String, String> orders;
    private TestInputTopic<String, String> payments;
    private TestInputTopic<String, String> inventory;
    private TestOutputTopic<String, String> output;
    private final Instant t0 = Instant.parse("2026-11-19T10:00:05Z");

    @BeforeEach
    void setUp() {
        StreamsBuilder builder = new StreamsBuilder();
        new AnalyticsTopology(mapper, SPIKE_THRESHOLD, 2).build(builder);
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "analytics-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        props.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0);
        driver = new TopologyTestDriver(builder.build(), props);
        orders = driver.createInputTopic(Topics.ORDER_EVENTS, new StringSerializer(), new StringSerializer());
        payments = driver.createInputTopic(Topics.PAYMENT_EVENTS, new StringSerializer(), new StringSerializer());
        inventory = driver.createInputTopic(Topics.INVENTORY_EVENTS, new StringSerializer(), new StringSerializer());
        output = driver.createOutputTopic(Topics.ANALYTICS_EVENTS, new StringDeserializer(), new StringDeserializer());
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    @Test
    void revenueIsAggregatedPerWindow_andRefundsAreSubtracted() {
        pay(EventTypes.PAYMENT_COMPLETED, 1, "100000", t0);
        pay(EventTypes.PAYMENT_COMPLETED, 2, "250000", t0.plusSeconds(20));
        pay(EventTypes.PAYMENT_COMPLETED, 3, "50000", t0.plusSeconds(70));   // phút kế tiếp
        pay(EventTypes.PAYMENT_REFUNDED, 1, "100000", t0.plusSeconds(75));

        Map<String, MetricUpdate> latest = latestMetrics();
        long minute0 = Instant.parse("2026-11-19T10:00:00Z").toEpochMilli();
        long minute1 = Instant.parse("2026-11-19T10:01:00Z").toEpochMilli();

        assertThat(latest.get("revenue_1m@" + minute0).value()).isEqualTo(350000);
        assertThat(latest.get("revenue_1m@" + minute0).count()).isEqualTo(2);
        assertThat(latest.get("revenue_1m@" + minute1).value()).isEqualTo(-50000); // 50k - hoàn 100k
        assertThat(latest.get("revenue_5m@" + minute0).value()).isEqualTo(300000);
        assertThat(latest.get("revenue_1h@" + Instant.parse("2026-11-19T10:00:00Z").toEpochMilli()).value())
                .isEqualTo(300000);
        assertThat(latest.get("revenue_total@null").value()).isEqualTo(300000);
        assertThat(latest.get("revenue_total@null").count()).isEqualTo(3);
    }

    @Test
    void ordersByStatus_followsLatestStatusOfEachOrder_andTopProductsAreComputed() {
        for (long id = 1; id <= 3; id++) {
            orderEvent(EventTypes.ORDER_CREATED, id, new OrderCreated(id, 7, lines(), new BigDecimal("300000"),
                    "MOCK_CARD", t0), t0.plusSeconds(id));
        }
        orderEvent(EventTypes.PAYMENT_REQUESTED, 1, new PaymentRequested(1, 7, new BigDecimal("300000"), "MOCK_CARD"), t0.plusSeconds(10));
        orderEvent(EventTypes.ORDER_CONFIRMED, 1, status(1, "AWAITING_PAYMENT", "CONFIRMED"), t0.plusSeconds(11));
        orderEvent(EventTypes.ORDER_CANCELLED, 2, status(2, "PENDING", "CANCELLED"), t0.plusSeconds(12));

        Map<String, MetricUpdate> latest = latestMetrics();
        assertThat(latest.get("orders_by_status:PENDING").value()).isEqualTo(1);
        assertThat(latest.get("orders_by_status:CONFIRMED").value()).isEqualTo(1);
        assertThat(latest.get("orders_by_status:CANCELLED").value()).isEqualTo(1);
        assertThat(latest.get("orders_by_status:AWAITING_PAYMENT").value()).isEqualTo(0);
        assertThat(latest.get("counter:orders_created").value()).isEqualTo(3);

        // Chỉ đơn đã xác nhận mới tính vào doanh số sản phẩm: 2 x 100k cho sản phẩm 11, 1 x 100k cho sản phẩm 12
        assertThat(latest.get("product_sales:11").count()).isEqualTo(2);
        assertThat(latest.get("product_sales:11").value()).isEqualTo(200000);
        assertThat(latest.get("product_sales:12").count()).isEqualTo(1);
    }

    @Test
    void orderSpike_raisesExactlyOneAlertPerWindow() {
        for (long id = 1; id <= 10; id++) {
            orderEvent(EventTypes.ORDER_CREATED, id, new OrderCreated(id, id, lines(), BigDecimal.TEN, "MOCK_CARD", t0),
                    t0.plusSeconds(id));
        }
        List<Alert> alerts = alerts();
        assertThat(alerts).filteredOn(a -> a.type().equals("ORDER_SPIKE")).hasSize(1);
    }

    @Test
    void lowStockEvent_becomesAlert() {
        EventEnvelope env = EventEnvelope.create(mapper, EventTypes.STOCK_LOW, "inventory-service", "5",
                new StockLow(5, "Áo khoác", 3, 10));
        inventory.pipeInput("5", json(env), t0);
        assertThat(alerts()).anyMatch(a -> a.type().equals("LOW_STOCK") && a.title().contains("Áo khoác"));
    }

    // ------------------------------------------------------------------ helpers

    private List<OrderLine> lines() {
        return List.of(new OrderLine(11, "Bàn phím", 2, new BigDecimal("100000")),
                new OrderLine(12, "Chuột", 1, new BigDecimal("100000")));
    }

    private OrderStatusChanged status(long id, String from, String to) {
        return new OrderStatusChanged(id, 7, from, to, new BigDecimal("300000"), null, lines());
    }

    private void pay(String type, long orderId, String amount, Instant at) {
        EventEnvelope env = EventEnvelope.create(mapper, type, "payment-service", String.valueOf(orderId),
                new PaymentResult(orderId, orderId, 7, new BigDecimal(amount), "MOCK_CARD", "X", null));
        payments.pipeInput(String.valueOf(orderId), json(env), at);
    }

    private void orderEvent(String type, long orderId, Object payload, Instant at) {
        EventEnvelope env = EventEnvelope.create(mapper, type, "order-service", String.valueOf(orderId), payload);
        orders.pipeInput(String.valueOf(orderId), json(env), at);
    }

    /** Giá trị mới nhất cho mỗi khóa: "metric:key" hoặc "metric@windowStart" với chỉ số theo cửa sổ. */
    private Map<String, MetricUpdate> latestMetrics() {
        Map<String, MetricUpdate> latest = new HashMap<>();
        for (KeyValue<String, String> kv : output.readKeyValuesToList()) {
            EventEnvelope env = read(kv.value);
            if (env.is(EventTypes.METRIC_UPDATED)) {
                MetricUpdate m = env.payloadAs(mapper, MetricUpdate.class);
                String key = m.windowStart() != null || m.metric().equals("revenue_total")
                        ? m.metric() + "@" + m.windowStart() : m.metric() + ":" + m.key();
                latest.put(key, m);
            }
        }
        return latest;
    }

    private List<Alert> alerts() {
        return output.readValuesToList().stream().map(this::read)
                .filter(e -> e.is(EventTypes.ALERT_RAISED))
                .map(e -> e.payloadAs(mapper, Alert.class))
                .toList();
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private EventEnvelope read(String s) {
        try {
            return mapper.readValue(s, EventEnvelope.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
