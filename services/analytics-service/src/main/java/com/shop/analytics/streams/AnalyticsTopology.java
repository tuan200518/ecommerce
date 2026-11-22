package com.shop.analytics.streams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.analytics.model.AnalyticsModels.Alert;
import com.shop.analytics.model.AnalyticsModels.MetricUpdate;
import com.shop.analytics.model.AnalyticsModels.ProductSale;
import com.shop.analytics.model.AnalyticsModels.SumCount;
import com.shop.analytics.model.AnalyticsModels.UserSet;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Repartitioned;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.WindowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Topology Kafka Streams của Analytics Service.
 * <p>
 * Đầu vào: order-events, payment-events, inventory-events, user-events, cart-events.
 * Đầu ra: topic analytics-events chứa các {@link MetricUpdate} (MetricUpdated) và {@link Alert} (AlertRaised).
 *
 * <pre>
 * payment-events ─▶ doanh thu theo cửa sổ 1m / 5m / 1h + tổng doanh thu
 *                ─▶ số thanh toán thất bại / phút  ─▶ cảnh báo PAYMENT_FAILURE_SPIKE
 * order-events   ─▶ số đơn / phút                  ─▶ cảnh báo ORDER_SPIKE
 *                ─▶ trạng thái mới nhất mỗi đơn (KTable) ─▶ đếm đơn theo trạng thái
 *                ─▶ doanh số theo sản phẩm (top sản phẩm bán chạy)
 * user/cart/order ─▶ người dùng hoạt động trong 5 phút (hopping window)
 * tất cả          ─▶ bộ đếm tổng (đơn tạo, xác nhận, hủy, thanh toán, thêm giỏ...) → phễu chuyển đổi
 * inventory-events ─▶ tồn kho mới nhất, cảnh báo LOW_STOCK
 * </pre>
 */
public class AnalyticsTopology {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsTopology.class);
    public static final String SOURCE = "analytics-streams";
    private static final Duration GRACE = Duration.ofSeconds(30);

    private static final Map<String, String> COUNTERS = Map.ofEntries(
            Map.entry(EventTypes.ORDER_CREATED, "orders_created"),
            Map.entry(EventTypes.ORDER_CONFIRMED, "orders_confirmed"),
            Map.entry(EventTypes.ORDER_CANCELLED, "orders_cancelled"),
            Map.entry(EventTypes.PAYMENT_COMPLETED, "payments_completed"),
            Map.entry(EventTypes.PAYMENT_FAILED, "payments_failed"),
            Map.entry(EventTypes.PAYMENT_REFUNDED, "payments_refunded"),
            Map.entry(EventTypes.CART_ITEM_ADDED, "cart_adds"),
            Map.entry(EventTypes.USER_REGISTERED, "users_registered"),
            Map.entry(EventTypes.USER_LOGGED_IN, "logins"));

    private final ObjectMapper mapper;
    private final long orderSpikeThreshold;
    private final long paymentFailureThreshold;
    private final Serde<String> str = Serdes.String();

    public AnalyticsTopology(ObjectMapper mapper, long orderSpikeThreshold, long paymentFailureThreshold) {
        this.mapper = mapper;
        this.orderSpikeThreshold = orderSpikeThreshold;
        this.paymentFailureThreshold = paymentFailureThreshold;
    }

    public void build(StreamsBuilder builder) {
        KStream<String, EventEnvelope> orders = events(builder, Topics.ORDER_EVENTS);
        KStream<String, EventEnvelope> payments = events(builder, Topics.PAYMENT_EVENTS);
        KStream<String, EventEnvelope> inventory = events(builder, Topics.INVENTORY_EVENTS);
        KStream<String, EventEnvelope> users = events(builder, Topics.USER_EVENTS);
        KStream<String, EventEnvelope> carts = events(builder, Topics.CART_EVENTS);

        revenue(payments);
        ordersPerMinute(orders);
        paymentFailures(payments);
        ordersByStatus(orders);
        productSales(orders);
        activeUsers(users, carts, orders);
        counters(orders, payments, carts, users);
        inventory(inventory);
    }

    // ------------------------------------------------------------------ Doanh thu (kịch bản A)

    private void revenue(KStream<String, EventEnvelope> payments) {
        KStream<String, Double> amounts = payments
                .filter((k, e) -> e.is(EventTypes.PAYMENT_COMPLETED) || e.is(EventTypes.PAYMENT_REFUNDED))
                .map((k, e) -> KeyValue.pair("all",
                        e.is(EventTypes.PAYMENT_COMPLETED) ? amount(e) : -amount(e)))
                .repartition(Repartitioned.with(str, Serdes.Double()).withName("revenue"));

        windowedRevenue(amounts, Duration.ofMinutes(1), "revenue_1m");
        windowedRevenue(amounts, Duration.ofMinutes(5), "revenue_5m");
        windowedRevenue(amounts, Duration.ofHours(1), "revenue_1h");

        amounts.groupByKey(Grouped.with(str, Serdes.Double()))
                .aggregate(SumCount::empty, (k, v, agg) -> agg.add(v),
                        Materialized.<String, SumCount, KeyValueStore<Bytes, byte[]>>as("revenue-total-store")
                                .withKeySerde(str).withValueSerde(json(SumCount.class)))
                .toStream()
                .map((k, agg) -> metric(new MetricUpdate("revenue_total", "all", null, null, null,
                        round(agg.sum()), agg.count(), null)))
                .to(Topics.ANALYTICS_EVENTS, Produced.with(str, str));
    }

    private void windowedRevenue(KStream<String, Double> amounts, Duration size, String metric) {
        amounts.groupByKey(Grouped.with(str, Serdes.Double()))
                .windowedBy(TimeWindows.ofSizeAndGrace(size, GRACE))
                .aggregate(SumCount::empty, (k, v, agg) -> agg.add(v),
                        Materialized.<String, SumCount, WindowStore<Bytes, byte[]>>as(metric + "-store")
                                .withKeySerde(str).withValueSerde(json(SumCount.class)))
                .toStream()
                .filter((wk, agg) -> agg != null)
                .map((wk, agg) -> metric(new MetricUpdate(metric, "all", null, wk.window().start(),
                        wk.window().end(), round(agg.sum()), agg.count(), null)))
                .to(Topics.ANALYTICS_EVENTS, Produced.with(str, str));
    }

    // ------------------------------------------------------------------ Đơn hàng / phút + phát hiện tăng đột biến

    private void ordersPerMinute(KStream<String, EventEnvelope> orders) {
        KStream<Windowed<String>, Long> perMinute = windowedCount(
                orders.filter((k, e) -> e.is(EventTypes.ORDER_CREATED)), Duration.ofMinutes(1), "orders_1m-store");

        perMinute.map((wk, c) -> metric(new MetricUpdate("orders_1m", "all", null, wk.window().start(),
                        wk.window().end(), c, c, null)))
                .to(Topics.ANALYTICS_EVENTS, Produced.with(str, str));

        // Mỗi bản ghi tăng count đúng 1 → điều kiện "== ngưỡng + 1" chỉ đúng một lần cho mỗi cửa sổ
        perMinute.filter((wk, c) -> c == orderSpikeThreshold + 1)
                .map((wk, c) -> alert(new Alert("ORDER_SPIKE:" + wk.window().start(), "ORDER_SPIKE", "WARNING",
                        "Đơn hàng tăng đột biến",
                        "Đã có hơn " + orderSpikeThreshold + " đơn trong phút bắt đầu lúc "
                                + Instant.ofEpochMilli(wk.window().start()),
                        wk.window().end(), Map.of("windowStart", wk.window().start(), "threshold", orderSpikeThreshold))))
                .to(Topics.ANALYTICS_EVENTS, Produced.with(str, str));
    }

    // ------------------------------------------------------------------ Thanh toán thất bại

    private void paymentFailures(KStream<String, EventEnvelope> payments) {
        KStream<Windowed<String>, Long> perMinute = windowedCount(
                payments.filter((k, e) -> e.is(EventTypes.PAYMENT_FAILED)), Duration.ofMinutes(1),
                "payment_failed_1m-store");

        perMinute.map((wk, c) -> metric(new MetricUpdate("payment_failed_1m", "all", null, wk.window().start(),
                        wk.window().end(), c, c, null)))
                .to(Topics.ANALYTICS_EVENTS, Produced.with(str, str));

        perMinute.filter((wk, c) -> c == paymentFailureThreshold + 1)
                .map((wk, c) -> alert(new Alert("PAYMENT_FAILURE_SPIKE:" + wk.window().start(),
                        "PAYMENT_FAILURE_SPIKE", "CRITICAL", "Thanh toán thất bại tăng cao",
                        "Hơn " + paymentFailureThreshold + " giao dịch thất bại trong 1 phút",
                        wk.window().end(), Map.of("windowStart", wk.window().start()))))
                .to(Topics.ANALYTICS_EVENTS, Produced.with(str, str));
    }

    private KStream<Windowed<String>, Long> windowedCount(KStream<String, EventEnvelope> source, Duration size,
                                                          String storeName) {
        return source.map((k, e) -> KeyValue.pair("all", e.eventId()))
                .groupByKey(Grouped.with(str, str))
                .windowedBy(TimeWindows.ofSizeAndGrace(size, GRACE))
                .count(Materialized.<String, Long, WindowStore<Bytes, byte[]>>as(storeName).withKeySerde(str))
                .toStream()
                .filter((wk, c) -> c != null);
    }

    // ------------------------------------------------------------------ Đơn theo trạng thái

    private void ordersByStatus(KStream<String, EventEnvelope> orders) {
        KTable<String, String> latestStatus = orders
                .flatMapValues(e -> {
                    String status = statusOf(e);
                    return status == null ? List.<String>of() : List.of(status);
                })
                .toTable(Materialized.<String, String, KeyValueStore<Bytes, byte[]>>as("order-status-store")
                        .withKeySerde(str).withValueSerde(str));

        // groupBy trên KTable: khi một đơn đổi trạng thái, trạng thái cũ tự động bị trừ đi 1
        latestStatus.groupBy((orderId, status) -> KeyValue.pair(status, orderId), Grouped.with(str, str))
                .count(Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as("orders-by-status-store")
                        .withKeySerde(str))
                .toStream()
                .filter((status, c) -> c != null)
                .map((status, c) -> metric(new MetricUpdate("orders_by_status", status, status, null, null, c, c, null)))
                .to(Topics.ANALYTICS_EVENTS, Produced.with(str, str));
    }

    private static String statusOf(EventEnvelope e) {
        return switch (e.eventType()) {
            case EventTypes.ORDER_CREATED -> "PENDING";
            case EventTypes.PAYMENT_REQUESTED -> "AWAITING_PAYMENT";
            case EventTypes.ORDER_CONFIRMED -> "CONFIRMED";
            case EventTypes.ORDER_CANCELLED -> "CANCELLED";
            case EventTypes.ORDER_STATUS_CHANGED -> e.payload().path("status").asText(null);
            default -> null;
        };
    }

    // ------------------------------------------------------------------ Sản phẩm bán chạy

    private void productSales(KStream<String, EventEnvelope> orders) {
        orders.filter((k, e) -> e.is(EventTypes.ORDER_CONFIRMED)
                        || (e.is(EventTypes.ORDER_CANCELLED)
                        && "CONFIRMED".equals(e.payload().path("previousStatus").asText())))
                .flatMap((k, e) -> {
                    int sign = e.is(EventTypes.ORDER_CONFIRMED) ? 1 : -1; // hủy sau khi xác nhận → trừ lại
                    List<KeyValue<String, ProductSale>> out = new ArrayList<>();
                    for (JsonNode item : e.payload().path("items")) {
                        long qty = item.path("quantity").asLong();
                        double price = item.path("unitPrice").asDouble();
                        out.add(KeyValue.pair(item.path("productId").asText(),
                                new ProductSale(item.path("productName").asText(null), sign * qty, sign * qty * price)));
                    }
                    return out;
                })
                .groupByKey(Grouped.with(str, json(ProductSale.class)))
                .aggregate(ProductSale::empty, (k, v, agg) -> agg.plus(v),
                        Materialized.<String, ProductSale, KeyValueStore<Bytes, byte[]>>as("product-sales-store")
                                .withKeySerde(str).withValueSerde(json(ProductSale.class)))
                .toStream()
                .map((productId, s) -> metric(new MetricUpdate("product_sales", productId, s.name(), null, null,
                        round(s.revenue()), s.quantity(), null)))
                .to(Topics.ANALYTICS_EVENTS, Produced.with(str, str));
    }

    // ------------------------------------------------------------------ Người dùng hoạt động

    private void activeUsers(KStream<String, EventEnvelope> users, KStream<String, EventEnvelope> carts,
                             KStream<String, EventEnvelope> orders) {
        KStream<String, String> logins = users.filter((k, e) -> e.is(EventTypes.USER_LOGGED_IN))
                .mapValues(e -> e.payload().path("userId").asText());
        KStream<String, String> cartActivity = carts.mapValues(e -> e.payload().path("userId").asText());
        KStream<String, String> buyers = orders.filter((k, e) -> e.is(EventTypes.ORDER_CREATED))
                .mapValues(e -> e.payload().path("userId").asText());

        logins.merge(cartActivity).merge(buyers)
                .filter((k, userId) -> userId != null && !userId.isBlank())
                .map((k, userId) -> KeyValue.pair("all", userId))
                .groupByKey(Grouped.with(str, str))
                .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMinutes(5), GRACE).advanceBy(Duration.ofMinutes(1)))
                .aggregate(UserSet::empty, (k, userId, set) -> set.with(userId),
                        Materialized.<String, UserSet, WindowStore<Bytes, byte[]>>as("active-users-store")
                                .withKeySerde(str).withValueSerde(json(UserSet.class)))
                .toStream()
                .filter((wk, set) -> set != null)
                .map((wk, set) -> metric(new MetricUpdate("active_users_5m", "all", null, wk.window().start(),
                        wk.window().end(), set.ids().size(), set.ids().size(), null)))
                .to(Topics.ANALYTICS_EVENTS, Produced.with(str, str));
    }

    // ------------------------------------------------------------------ Bộ đếm tổng (phễu chuyển đổi)

    private void counters(KStream<String, EventEnvelope> orders, KStream<String, EventEnvelope> payments,
                          KStream<String, EventEnvelope> carts, KStream<String, EventEnvelope> users) {
        orders.merge(payments).merge(carts).merge(users)
                .filter((k, e) -> COUNTERS.containsKey(e.eventType()))
                .map((k, e) -> KeyValue.pair(COUNTERS.get(e.eventType()), e.eventId()))
                .groupByKey(Grouped.with(str, str))
                .count(Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as("counters-store").withKeySerde(str))
                .toStream()
                .filter((name, c) -> c != null)
                .map((name, c) -> metric(new MetricUpdate("counter", name, name, null, null, c, c, null)))
                .to(Topics.ANALYTICS_EVENTS, Produced.with(str, str));
    }

    // ------------------------------------------------------------------ Tồn kho

    private void inventory(KStream<String, EventEnvelope> inventory) {
        inventory.filter((k, e) -> e.is(EventTypes.STOCK_UPDATED))
                .map((k, e) -> {
                    JsonNode p = e.payload();
                    return metric(new MetricUpdate("stock", p.path("productId").asText(),
                            p.path("productName").asText(null), null, null,
                            p.path("available").asInt(), p.path("reserved").asInt(),
                            Map.of("threshold", p.path("lowStockThreshold").asInt(),
                                    "reserved", p.path("reserved").asInt())));
                })
                .to(Topics.ANALYTICS_EVENTS, Produced.with(str, str));

        inventory.filter((k, e) -> e.is(EventTypes.STOCK_LOW))
                .map((k, e) -> {
                    JsonNode p = e.payload();
                    return alert(new Alert("LOW_STOCK:" + e.eventId(), "LOW_STOCK", "WARNING",
                            "Tồn kho thấp: " + p.path("productName").asText(),
                            "Còn " + p.path("available").asInt() + " (ngưỡng " + p.path("threshold").asInt() + ")",
                            e.occurredAt() == null ? System.currentTimeMillis() : e.occurredAt().toEpochMilli(),
                            Map.of("productId", p.path("productId").asLong())));
                })
                .to(Topics.ANALYTICS_EVENTS, Produced.with(str, str));
    }

    // ------------------------------------------------------------------ Helpers

    private KStream<String, EventEnvelope> events(StreamsBuilder builder, String topic) {
        return builder.stream(topic, Consumed.with(str, str))
                .flatMapValues(json -> {
                    try {
                        EventEnvelope env = mapper.readValue(json, EventEnvelope.class);
                        return env.eventType() == null || env.payload() == null ? List.<EventEnvelope>of() : List.of(env);
                    } catch (Exception ex) {
                        log.warn("Bỏ qua message không hợp lệ trên {}: {}", topic, ex.getMessage());
                        return List.<EventEnvelope>of();
                    }
                });
    }

    private KeyValue<String, String> metric(MetricUpdate m) {
        return KeyValue.pair(m.metric() + ":" + m.key(), envelope(EventTypes.METRIC_UPDATED, m.metric() + ":" + m.key(), m));
    }

    private KeyValue<String, String> alert(Alert a) {
        return KeyValue.pair("alert:" + a.type(), envelope(EventTypes.ALERT_RAISED, a.id(), a));
    }

    private String envelope(String type, String aggregateId, Object payload) {
        try {
            return mapper.writeValueAsString(EventEnvelope.create(mapper, type, SOURCE, aggregateId, payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> JsonSerde<T> json(Class<T> type) {
        return new JsonSerde<>(mapper, type);
    }

    private static double amount(EventEnvelope e) {
        return e.payload().path("amount").asDouble();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
