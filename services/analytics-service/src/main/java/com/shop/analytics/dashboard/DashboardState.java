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
                       long paymentsCompleted, long paymentsFailed, double cartToOrderRate, double averageOrderValue) {
    }

    public record Snapshot(long generatedAt, Kpis kpis,
                           List<Point> revenuePerMinute, List<Point> revenuePer5Minutes, List<Point> revenuePerHour,
                           List<Point> ordersPerMinute, List<Point> paymentFailuresPerMinute,
                           Map<String, Long> ordersByStatus, List<ProductStat> topProducts,
                           List<StockInfo> lowStock, Map<String, Long> counters, List<Alert> alerts,
                           KafkaStats kafka) {
    }

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
    private KafkaStats kafka;
    private boolean dirty;

    public synchronized void apply(MetricUpdate m) {
        dirty = true;
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
        return true;
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
        List<StockInfo> low = stock.values().stream()
                .filter(s -> s.available() <= s.threshold())
                .sorted(Comparator.comparingInt(StockInfo::available))
                .toList();
        Kpis kpis = new Kpis(created, confirmed, cancelled, revenueTotal, currentActiveUsers(now), low.size(),
                completed + failed == 0 ? 0 : round(100.0 * completed / (completed + failed)),
                completed, failed,
                cartAdds == 0 ? 0 : round(100.0 * created / cartAdds),
                revenuePayments == 0 ? 0 : round(revenueTotal / revenuePayments));
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
