package com.shop.analytics.model;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Các kiểu dữ liệu của luồng phân tích. */
public final class AnalyticsModels {

    private AnalyticsModels() {
    }

    /**
     * Một giá trị chỉ số đã tổng hợp, phát lên topic analytics-events (eventType = MetricUpdated).
     *
     * @param metric      tên chỉ số (revenue_1m, orders_1m, orders_by_status, product_sales, counter, stock...)
     * @param key         khóa trong chỉ số (vd. productId, tên trạng thái, "all")
     * @param windowStart epoch ms của cửa sổ thời gian (null nếu không theo cửa sổ)
     */
    public record MetricUpdate(String metric, String key, String label, Long windowStart, Long windowEnd,
                               double value, long count, Map<String, Object> attrs) {
    }

    /** Cảnh báo vận hành (eventType = AlertRaised). */
    public record Alert(String id, String type, String severity, String title, String message, long at,
                        Map<String, Object> attrs) {
    }

    /** Bộ tích lũy tổng + đếm cho cửa sổ doanh thu. */
    public record SumCount(double sum, long count) {
        public static SumCount empty() {
            return new SumCount(0, 0);
        }

        public SumCount add(double value) {
            return new SumCount(sum + value, value > 0 ? count + 1 : count);
        }
    }

    /** Bộ tích lũy doanh số theo sản phẩm. */
    public record ProductSale(String name, long quantity, double revenue) {
        public static ProductSale empty() {
            return new ProductSale(null, 0, 0);
        }

        public ProductSale plus(ProductSale other) {
            return new ProductSale(other.name != null ? other.name : name,
                    quantity + other.quantity, revenue + other.revenue);
        }
    }

    /** Tập userId duy nhất trong cửa sổ (đếm người dùng đang hoạt động). */
    public record UserSet(Set<String> ids) {
        public static UserSet empty() {
            return new UserSet(new TreeSet<>());
        }

        public UserSet with(String id) {
            if (ids.contains(id)) {
                return this;
            }
            TreeSet<String> next = new TreeSet<>(ids);
            next.add(id);
            return new UserSet(next);
        }
    }
}
