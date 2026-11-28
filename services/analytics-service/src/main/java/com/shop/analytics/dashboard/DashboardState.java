package com.shop.analytics.dashboard;

import com.shop.analytics.model.AnalyticsModels.Alert;
import com.shop.analytics.model.AnalyticsModels.MetricUpdate;
import com.shop.analytics.monitor.KafkaStats;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Trạng thái Dashboard trong bộ nhớ, được dựng lại từ topic analytics-events (kết quả của Kafka Streams).
 * Thread-safe ở mức phương thức.
 * <p>
 * Kèm theo vị trí (offset) đã áp dụng trên từng partition: {@link DashboardSnapshotStore} lưu định kỳ
 * {@link #export()} xuống CSDL, khi khởi động lại thì {@link #restore} rồi consumer chỉ đọc tiếp phần còn thiếu
 * thay vì đọc lại toàn bộ topic.
 */
@Component
public class DashboardState {

    public record Point(long t, double value, long count) {
    }

    public record ProductStat(long productId, String name, long quantity, double revenue) {
    }

    public record StockInfo(long productId, String name, int available, int reserved, int threshold) {
    }

    public record Kpis(long totalOrders, long confirmedOrders, long cancelledOrders, double revenue,
                       long activeUsers, long lowStockProducts, double paymentSuccessRate,
                       long paymentsCompleted, long paymentsFailed, double cartToOrderRate, double averageOrderValue,
                       long productViews, double viewToOrderRate, long flaggedOrders, long shipmentsDelivered,
                       long reviews, double promotionDiscount, long discountedOrders, long ticketsCreated,
                       long ticketsResolved) {
    }

    public record Snapshot(long generatedAt, Kpis kpis,
                           List<Point> revenuePerMinute, List<Point> revenuePer5Minutes, List<Point> revenuePerHour,
                           List<Point> ordersPerMinute, List<Point> paymentFailuresPerMinute,
                           Map<String, Long> ordersByStatus, List<ProductStat> topProducts,
                           List<StockInfo> lowStock, Map<String, Long> counters, List<Alert> alerts,
                           KafkaStats kafka) {
    }

    /** Điểm theo cửa sổ thời gian (người dùng hoạt động: khóa = đầu cửa sổ, point.t = cuối cửa sổ). */
    public record WindowPoint(long windowStart, Point point) {
    }

    /** Bản sao trạng thái để lưu xuống CSDL (JSON). Đổi cấu trúc → tăng {@link #FORMAT}, bản cũ bị bỏ qua. */
    public record Persisted(int format, String topicId, Map<Integer, Long> offsets,
                            Map<String, List<Point>> series, List<WindowPoint> activeUsers,
                            Map<String, Long> statusCounts, List<ProductStat> products, List<StockInfo> stock,
                            Map<String, Long> counters, List<Alert> alerts, double revenueTotal, long revenuePayments,
                            double promotionDiscount, long discountedOrders) {
    }

    public static final int FORMAT = 1;
    private static final int MAX_ALERTS = 50;

    private final Map<String, NavigableMap<Long, Point>> series = new HashMap<>();
    private final NavigableMap<Long, Point> activeUsers = new TreeMap<>();
    private final Map<String, Long> statusCounts = new LinkedHashMap<>();
    private final Map<Long, ProductStat> products = new HashMap<>();
    private final Map<Long, StockInfo> stock = new HashMap<>();
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private final Deque<Alert> alerts = new ArrayDeque<>();
    private double revenueTotal;
    private long revenuePayments;
    private double promotionDiscount;
    private long discountedOrders;
    private KafkaStats kafka;
    private boolean dirty;
    /** partition → offset cuối cùng đã áp dụng (của topic analytics-events có id {@link #topicId}). */
    private final Map<Integer, Long> offsets = new HashMap<>();
    private String topicId;
    /** Tăng mỗi khi dữ liệu thay đổi – để biết có cần lưu snapshot mới hay không. */
    private long version;

    public synchronized void apply(MetricUpdate m) {
        dirty = true;
        version++;
        switch (m.metric()) {
            case "revenue_1m" -> putSeries(m, 180);
            case "revenue_5m" -> putSeries(m, 144);
            case "revenue_1h" -> putSeries(m, 72);
            case "orders_1m" -> putSeries(m, 180);
            case "payment_failed_1m" -> putSeries(m, 180);
            case "active_users_5m" -> {
                activeUsers.put(m.windowStart(), new Point(m.windowEnd(), m.value(), m.count()));
                while (activeUsers.size() > 30) {
                    activeUsers.pollFirstEntry();
                }
            }
            case "revenue_total" -> {
                revenueTotal = m.value();
                revenuePayments = m.count();
            }
            case "promotion_discount" -> {
                promotionDiscount = m.value();
                discountedOrders = m.count();
            }
            case "orders_by_status" -> statusCounts.put(m.key(), (long) m.value());
            case "counter" -> counters.put(m.key(), (long) m.value());
            case "product_sales" -> {
                long id = Long.parseLong(m.key());
                products.put(id, new ProductStat(id, m.label(), m.count(), m.value()));
            }
            case "stock" -> {
                long id = Long.parseLong(m.key());
                int threshold = m.attrs() == null ? 0 : ((Number) m.attrs().getOrDefault("threshold", 0)).intValue();
                stock.put(id, new StockInfo(id, m.label(), (int) m.value(), (int) m.count(), threshold));
            }
            default -> {
                // chỉ số chưa hỗ trợ hiển thị
            }
        }
    }

    public synchronized boolean apply(Alert a) {
        if (alerts.stream().anyMatch(x -> x.id().equals(a.id()))) {
            return false;
        }
        alerts.addFirst(a);
        while (alerts.size() > MAX_ALERTS) {
            alerts.removeLast();
        }
        dirty = true;
        version++;
        return true;
    }

    // ------------------------------------------------------------------ Vị trí đọc + snapshot

    /** Ghi nhận đã áp dụng xong message tại (partition, offset). */
    public synchronized void markApplied(int partition, long offset) {
        offsets.merge(partition, offset, Math::max);
        version++;
    }

    /**
     * Offset tiếp theo cần đọc cho mỗi partition đã biết. Nếu topic hiện tại có id khác với topic mà trạng thái
     * được dựng từ đó (topic bị xóa/tạo lại) thì trạng thái cũ không còn đúng → xóa sạch và đọc lại từ đầu.
     *
     * @param currentTopicId id của topic analytics-events hiện tại, null nếu không xác định được (giữ nguyên trạng thái)
     */
    public synchronized Map<Integer, Long> resumePositions(String currentTopicId) {
        if (currentTopicId != null) {
            if (topicId != null && !topicId.equals(currentTopicId)) {
                reset();
            }
            topicId = currentTopicId;
        }
        Map<Integer, Long> next = new HashMap<>();
        offsets.forEach((p, o) -> next.put(p, o + 1));
        return next;
    }

    public synchronized long version() {
        return version;
    }

    public synchronized boolean isEmpty() {
        return offsets.isEmpty() && counters.isEmpty() && series.isEmpty();
    }

    public synchronized Persisted export() {
        Map<String, List<Point>> s = new LinkedHashMap<>();
        series.forEach((k, v) -> s.put(k, new ArrayList<>(v.values())));
        List<WindowPoint> users = new ArrayList<>();
        activeUsers.forEach((start, p) -> users.add(new WindowPoint(start, p)));
        return new Persisted(FORMAT, topicId, new HashMap<>(offsets), s, users, new LinkedHashMap<>(statusCounts),
                new ArrayList<>(products.values()), new ArrayList<>(stock.values()), new LinkedHashMap<>(counters),
                new ArrayList<>(alerts), revenueTotal, revenuePayments, promotionDiscount, discountedOrders);
    }

    public synchronized void restore(Persisted p) {
        reset();
        topicId = p.topicId();
        if (p.offsets() != null) {
            offsets.putAll(p.offsets());
        }
        if (p.series() != null) {
            p.series().forEach((k, points) -> {
                NavigableMap<Long, Point> m = new TreeMap<>();
                points.forEach(pt -> m.put(pt.t(), pt));
                series.put(k, m);
            });
        }
        if (p.activeUsers() != null) {
            p.activeUsers().forEach(w -> activeUsers.put(w.windowStart(), w.point()));
        }
        if (p.statusCounts() != null) {
            statusCounts.putAll(p.statusCounts());
        }
        if (p.products() != null) {
            p.products().forEach(x -> products.put(x.productId(), x));
        }
        if (p.stock() != null) {
            p.stock().forEach(x -> stock.put(x.productId(), x));
        }
        if (p.counters() != null) {
            counters.putAll(p.counters());
        }
        if (p.alerts() != null) {
            alerts.addAll(p.alerts());
        }
        revenueTotal = p.revenueTotal();
        revenuePayments = p.revenuePayments();
        promotionDiscount = p.promotionDiscount();
        discountedOrders = p.discountedOrders();
        dirty = true;
    }

    /** Xóa toàn bộ dữ liệu (giữ thông tin Kafka monitor). */
    public synchronized void reset() {
        series.clear();
        activeUsers.clear();
        statusCounts.clear();
        products.clear();
        stock.clear();
        counters.clear();
        alerts.clear();
        offsets.clear();
        topicId = null;
        revenueTotal = 0;
        revenuePayments = 0;
        promotionDiscount = 0;
        discountedOrders = 0;
        dirty = true;
        version++;
    }

    public synchronized void updateKafka(KafkaStats stats) {
        this.kafka = stats;
        dirty = true;
    }

    /** @return true nếu có thay đổi kể từ lần gọi trước (dùng để throttle việc đẩy SSE). */
    public synchronized boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    public synchronized Snapshot snapshot() {
        long now = System.currentTimeMillis();
        long created = counters.getOrDefault("orders_created", 0L);
        long confirmed = counters.getOrDefault("orders_confirmed", 0L);
        long cancelled = counters.getOrDefault("orders_cancelled", 0L);
        long completed = counters.getOrDefault("payments_completed", 0L);
        long failed = counters.getOrDefault("payments_failed", 0L);
        long cartAdds = counters.getOrDefault("cart_adds", 0L);
        long views = counters.getOrDefault("product_views", 0L);
        List<StockInfo> low = stock.values().stream()
                .filter(s -> s.available() <= s.threshold())
                .sorted(Comparator.comparingInt(StockInfo::available))
                .toList();
        Kpis kpis = new Kpis(created, confirmed, cancelled, revenueTotal, currentActiveUsers(now), low.size(),
                completed + failed == 0 ? 0 : round(100.0 * completed / (completed + failed)),
                completed, failed,
                cartAdds == 0 ? 0 : round(100.0 * created / cartAdds),
                revenuePayments == 0 ? 0 : round(revenueTotal / revenuePayments),
                views, views == 0 ? 0 : round(100.0 * created / views),
                counters.getOrDefault("orders_flagged", 0L), counters.getOrDefault("shipments_delivered", 0L),
                counters.getOrDefault("reviews", 0L), promotionDiscount, discountedOrders,
                counters.getOrDefault("tickets_created", 0L), counters.getOrDefault("tickets_resolved", 0L));
        return new Snapshot(now, kpis,
                list("revenue_1m", 60), list("revenue_5m", 48), list("revenue_1h", 24),
                list("orders_1m", 60), list("payment_failed_1m", 60),
                new LinkedHashMap<>(statusCounts),
                products.values().stream().sorted(Comparator.comparingDouble(ProductStat::revenue).reversed())
                        .limit(10).toList(),
                low.stream().limit(20).toList(),
                new LinkedHashMap<>(counters),
                new ArrayList<>(alerts),
                kafka);
    }

    /** Cửa sổ hopping 5 phút đang bao phủ thời điểm hiện tại và bắt đầu sớm nhất (≈ 5 phút gần nhất). */
    private long currentActiveUsers(long now) {
        return activeUsers.values().stream()
                .filter(p -> p.t() > now)
                .findFirst()
                .map(p -> (long) p.value())
                .orElse(0L);
    }

    private void putSeries(MetricUpdate m, int keep) {
        NavigableMap<Long, Point> s = series.computeIfAbsent(m.metric(), k -> new TreeMap<>());
        s.put(m.windowStart(), new Point(m.windowStart(), m.value(), m.count()));
        while (s.size() > keep) {
            s.pollFirstEntry();
        }
    }

    private List<Point> list(String metric, int last) {
        NavigableMap<Long, Point> s = series.getOrDefault(metric, new TreeMap<>());
        List<Point> all = new ArrayList<>(s.values());
        return all.subList(Math.max(0, all.size() - last), all.size());
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
