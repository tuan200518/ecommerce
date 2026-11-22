package com.shop.analytics.dashboard;

import com.shop.analytics.model.AnalyticsModels.Alert;
import com.shop.analytics.model.AnalyticsModels.MetricUpdate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Lưu dữ liệu tổng hợp (upsert theo khóa → idempotent khi replay topic analytics-events).
 * Báo cáo đọc từ đây thay vì truy vấn nặng lên database nghiệp vụ.
 */
@Repository
public class AnalyticsRepository {

    private final JdbcTemplate jdbc;

    public AnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(MetricUpdate m) {
        Timestamp now = Timestamp.from(Instant.now());
        switch (m.metric()) {
            case "revenue_1m", "revenue_5m", "revenue_1h" -> jdbc.update("""
                            INSERT INTO sales_aggregates (window_size, window_start, window_end, revenue, payments_count, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            ON CONFLICT (window_size, window_start)
                            DO UPDATE SET revenue = EXCLUDED.revenue, payments_count = EXCLUDED.payments_count,
                                          updated_at = EXCLUDED.updated_at""",
                    m.metric().substring("revenue_".length()), ts(m.windowStart()), ts(m.windowEnd()),
                    BigDecimal.valueOf(m.value()), m.count(), now);
            case "product_sales" -> jdbc.update("""
                            INSERT INTO product_statistics (product_id, product_name, quantity_sold, revenue, updated_at)
                            VALUES (?, ?, ?, ?, ?)
                            ON CONFLICT (product_id)
                            DO UPDATE SET product_name = EXCLUDED.product_name, quantity_sold = EXCLUDED.quantity_sold,
                                          revenue = EXCLUDED.revenue, updated_at = EXCLUDED.updated_at""",
                    Long.parseLong(m.key()), m.label(), m.count(), BigDecimal.valueOf(m.value()), now);
            case "counter", "orders_by_status", "revenue_total" -> jdbc.update("""
                            INSERT INTO event_statistics (metric, metric_key, metric_value, event_count, updated_at)
                            VALUES (?, ?, ?, ?, ?)
                            ON CONFLICT (metric, metric_key)
                            DO UPDATE SET metric_value = EXCLUDED.metric_value, event_count = EXCLUDED.event_count, updated_at = EXCLUDED.updated_at""",
                    m.metric(), m.key(), m.value(), m.count(), now);
            default -> {
            }
        }
    }

    public void save(Alert a) {
        jdbc.update("""
                        INSERT INTO analytics_alerts (alert_id, type, severity, title, message, created_at)
                        VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (alert_id) DO NOTHING""",
                a.id(), a.type(), a.severity(), a.title(), a.message(), ts(a.at()));
    }

    public List<Map<String, Object>> salesReport(String windowSize, Instant from, Instant to) {
        return jdbc.queryForList("""
                        SELECT window_start AS "windowStart", window_end AS "windowEnd", revenue, payments_count AS "payments"
                        FROM sales_aggregates WHERE window_size = ? AND window_start >= ? AND window_start < ?
                        ORDER BY window_start""",
                windowSize, Timestamp.from(from), Timestamp.from(to));
    }

    public List<Map<String, Object>> productReport(int limit) {
        return jdbc.queryForList("""
                        SELECT product_id AS "productId", product_name AS "productName",
                               quantity_sold AS "quantitySold", revenue
                        FROM product_statistics ORDER BY revenue DESC LIMIT ?""", limit);
    }

    public List<Map<String, Object>> alertHistory(int limit) {
        return jdbc.queryForList("""
                SELECT alert_id AS "id", type, severity, title, message, created_at AS "createdAt"
                FROM analytics_alerts ORDER BY created_at DESC LIMIT ?""", limit);
    }

    private static Timestamp ts(Long epochMs) {
        return epochMs == null ? null : new Timestamp(epochMs);
    }
}
