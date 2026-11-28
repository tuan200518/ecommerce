package com.shop.recommendation.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Truy cập CSDL của Recommendation Service (JdbcTemplate, SQL chạy được trên PostgreSQL và H2).
 * <p>
 * Mọi bộ đếm được tăng bằng "INSERT ... ON CONFLICT DO NOTHING" + "UPDATE ... SET x = x + ?" nên an toàn
 * khi nhiều consumer thread cùng cập nhật một dòng (không đọc-sửa-ghi).
 */
@Repository
public class RecommendationStore {

    public record CatalogItem(long productId, String name, Long categoryId, String categoryName, BigDecimal price,
                              String imageUrl, boolean active) {
    }

    public record Stats(long buyers, long viewers, long ratingSum, long ratingCount) {
        public static final Stats EMPTY = new Stats(0, 0, 0, 0);
    }

    public record Pair(long relatedId, double weight) {
    }

    public record DailyStat(long productId, LocalDate day, long views, long carts, long purchases) {
    }

    public record Interaction(Long productId, Long categoryId, String kind, String query, Instant occurredAt) {
    }

    public record Click(long id, long productId, String placement) {
    }

    public record PlacementRow(String placement, long requests, long impressions, long clicks, long conversions,
                               double revenue) {
    }

    public record DayRow(LocalDate day, long impressions, long clicks, long conversions) {
    }

    public record TopPair(long productId, long relatedId, double weight) {
    }

    /** Ngày thống kê tính theo giờ Việt Nam. */
    public static final java.time.ZoneId ZONE = java.time.ZoneId.of("Asia/Ho_Chi_Minh");

    public static LocalDate day(Instant at) {
        return LocalDate.ofInstant(at, ZONE);
    }

    /** Cột được phép tăng (không bao giờ ghép chuỗi từ dữ liệu người dùng). */
    private static final Set<String> STAT_COLUMNS = Set.of("buyers", "viewers");
    private static final Set<String> DAILY_COLUMNS = Set.of("views", "carts", "purchases");

    private final JdbcTemplate jdbc;

    public RecommendationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ Danh mục

    public void upsertProduct(CatalogItem p, Instant at) {
        Timestamp ts = Timestamp.from(at);
        int updated = jdbc.update("""
                        UPDATE rec_products SET name = ?, category_id = ?, category_name = ?, price = ?, image_url = ?,
                               active = ?, updated_at = ? WHERE product_id = ?""",
                p.name(), p.categoryId(), p.categoryName(), p.price(), p.imageUrl(), p.active(), ts, p.productId());
        if (updated == 0) {
            jdbc.update("""
                            INSERT INTO rec_products (product_id, name, category_id, category_name, price, image_url, active, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                    p.productId(), p.name(), p.categoryId(), p.categoryName(), p.price(), p.imageUrl(), p.active(), ts);
        }
    }

    public Map<Long, CatalogItem> catalog() {
        Map<Long, CatalogItem> out = new HashMap<>();
        jdbc.query("SELECT product_id, name, category_id, category_name, price, image_url, active FROM rec_products",
                rs -> {
                    long id = rs.getLong("product_id");
                    long cat = rs.getLong("category_id");
                    Long category = rs.wasNull() ? null : cat;
                    out.put(id, new CatalogItem(id, rs.getString("name"), category, rs.getString("category_name"),
                            rs.getBigDecimal("price"), rs.getString("image_url"), rs.getBoolean("active")));
                });
        return out;
    }

    // ------------------------------------------------------------------ Thống kê sản phẩm

    public void incrementStat(long productId, String column, long delta) {
        if (!STAT_COLUMNS.contains(column)) {
            throw new IllegalArgumentException(column);
        }
        jdbc.update("INSERT INTO rec_product_stats (product_id) VALUES (?) ON CONFLICT DO NOTHING", productId);
        jdbc.update("UPDATE rec_product_stats SET " + column + " = " + column + " + ? WHERE product_id = ?", delta, productId);
    }

    public void addRating(long productId, long sumDelta, long countDelta) {
        jdbc.update("INSERT INTO rec_product_stats (product_id) VALUES (?) ON CONFLICT DO NOTHING", productId);
        jdbc.update("""
                UPDATE rec_product_stats SET rating_sum = rating_sum + ?, rating_count = rating_count + ?
                WHERE product_id = ?""", sumDelta, countDelta, productId);
    }

    public Map<Long, Stats> stats() {
        Map<Long, Stats> out = new HashMap<>();
        jdbc.query("SELECT product_id, buyers, viewers, rating_sum, rating_count FROM rec_product_stats",
                rs -> {
                    out.put(rs.getLong(1), new Stats(rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
                });
        return out;
    }

    // ------------------------------------------------------------------ Ma trận đồng xuất hiện

    public void incrementPair(long productId, long relatedId, String kind, double delta) {
        jdbc.update("INSERT INTO rec_pairs (product_id, related_id, kind, weight) VALUES (?, ?, ?, 0) ON CONFLICT DO NOTHING",
                productId, relatedId, kind);
        jdbc.update("UPDATE rec_pairs SET weight = weight + ? WHERE product_id = ? AND related_id = ? AND kind = ?",
                delta, productId, relatedId, kind);
    }

    public List<Pair> pairs(long productId, String kind, int limit) {
        return jdbc.query("""
                        SELECT related_id, weight FROM rec_pairs WHERE product_id = ? AND kind = ? AND weight > 0
                        ORDER BY weight DESC, related_id LIMIT ?""",
                (rs, i) -> new Pair(rs.getLong(1), rs.getDouble(2)), productId, kind, limit);
    }

    public List<TopPair> topPairs(String kind, int limit) {
        return jdbc.query("""
                        SELECT product_id, related_id, weight FROM rec_pairs
                        WHERE kind = ? AND product_id < related_id AND weight > 0
                        ORDER BY weight DESC, product_id, related_id LIMIT ?""",
                (rs, i) -> new TopPair(rs.getLong(1), rs.getLong(2), rs.getDouble(3)), kind, limit);
    }

    // ------------------------------------------------------------------ Độ phổ biến theo ngày

    public void incrementDaily(long productId, LocalDate day, String column, long delta) {
        if (!DAILY_COLUMNS.contains(column)) {
            throw new IllegalArgumentException(column);
        }
        jdbc.update("INSERT INTO rec_daily (product_id, stat_day) VALUES (?, ?) ON CONFLICT DO NOTHING",
                productId, Date.valueOf(day));
        jdbc.update("UPDATE rec_daily SET " + column + " = " + column + " + ? WHERE product_id = ? AND stat_day = ?",
                delta, productId, Date.valueOf(day));
    }

    public List<DailyStat> daily(LocalDate from) {
        return jdbc.query("SELECT product_id, stat_day, views, carts, purchases FROM rec_daily WHERE stat_day >= ?",
                (rs, i) -> new DailyStat(rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5)), Date.valueOf(from));
    }

    // ------------------------------------------------------------------ Hành vi khách hàng

    public void addInteraction(long userId, Long productId, Long categoryId, String kind, String query, Instant at) {
        jdbc.update("""
                        INSERT INTO rec_interactions (user_id, product_id, category_id, kind, search_query, occurred_at)
                        VALUES (?, ?, ?, ?, ?, ?)""",
                userId, productId, categoryId, kind, query, Timestamp.from(at));
    }

    /** Sản phẩm khách đã xem trong [from, to], mới nhất trước (không trùng). */
    public List<Long> recentViews(long userId, Instant from, Instant to) {
        return jdbc.queryForList("""
                                SELECT product_id FROM rec_interactions
                                WHERE user_id = ? AND kind = 'VIEW' AND product_id IS NOT NULL
                                  AND occurred_at >= ? AND occurred_at <= ?
                                ORDER BY occurred_at DESC LIMIT 50""",
                        Long.class, userId, Timestamp.from(from), Timestamp.from(to))
                .stream().distinct().toList();
    }

    public List<Interaction> interactions(long userId, Instant from, int limit) {
        return jdbc.query("""
                        SELECT product_id, category_id, kind, search_query, occurred_at FROM rec_interactions
                        WHERE user_id = ? AND occurred_at >= ? ORDER BY occurred_at DESC LIMIT ?""",
                (rs, i) -> {
                    long p = rs.getLong(1);
                    Long product = rs.wasNull() ? null : p;
                    long c = rs.getLong(2);
                    Long category = rs.wasNull() ? null : c;
                    return new Interaction(product, category, rs.getString(3), rs.getString(4),
                            rs.getTimestamp(5).toInstant());
                }, userId, Timestamp.from(from), limit);
    }

    // ------------------------------------------------------------------ Đo hiệu quả gợi ý

    private void ensurePlacement(LocalDate day, String placement) {
        jdbc.update("INSERT INTO rec_placement_stats (stat_day, placement) VALUES (?, ?) ON CONFLICT DO NOTHING",
                Date.valueOf(day), placement);
    }

    public void served(LocalDate day, String placement, int impressions) {
        ensurePlacement(day, placement);
        jdbc.update("""
                UPDATE rec_placement_stats SET requests = requests + 1, impressions = impressions + ?
                WHERE stat_day = ? AND placement = ?""", impressions, Date.valueOf(day), placement);
    }

    public void clicked(LocalDate day, String placement) {
        ensurePlacement(day, placement);
        jdbc.update("UPDATE rec_placement_stats SET clicks = clicks + 1 WHERE stat_day = ? AND placement = ?",
                Date.valueOf(day), placement);
    }

    public void converted(LocalDate day, String placement, double revenue) {
        ensurePlacement(day, placement);
        jdbc.update("""
                UPDATE rec_placement_stats SET conversions = conversions + 1, revenue = revenue + ?
                WHERE stat_day = ? AND placement = ?""", revenue, Date.valueOf(day), placement);
    }

    public void addClick(long userId, long productId, String placement, Instant at) {
        jdbc.update("INSERT INTO rec_clicks (user_id, product_id, placement, clicked_at) VALUES (?, ?, ?, ?)",
                userId, productId, placement, Timestamp.from(at));
    }

    /** Lượt bấm chưa quy đổi của khách vào các sản phẩm trong đơn, kể từ {@code since} (mới nhất trước). */
    public List<Click> openClicks(long userId, Collection<Long> productIds, Instant since) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        String in = productIds.stream().map(x -> "?").collect(Collectors.joining(","));
        Object[] args = new Object[productIds.size() + 2];
        args[0] = userId;
        args[1] = Timestamp.from(since);
        int i = 2;
        for (Long id : productIds) {
            args[i++] = id;
        }
        return jdbc.query("SELECT id, product_id, placement FROM rec_clicks WHERE user_id = ? AND converted = FALSE"
                        + " AND clicked_at >= ? AND product_id IN (" + in + ") ORDER BY clicked_at DESC",
                (rs, n) -> new Click(rs.getLong(1), rs.getLong(2), rs.getString(3)), args);
    }

    public void markConverted(long clickId) {
        jdbc.update("UPDATE rec_clicks SET converted = TRUE WHERE id = ?", clickId);
    }

    public List<PlacementRow> placementReport(LocalDate from) {
        return jdbc.query("""
                        SELECT placement, SUM(requests), SUM(impressions), SUM(clicks), SUM(conversions), SUM(revenue)
                        FROM rec_placement_stats WHERE stat_day >= ? GROUP BY placement ORDER BY placement""",
                (rs, i) -> new PlacementRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getDouble(6)), Date.valueOf(from));
    }

    public List<DayRow> dailyReport(LocalDate from) {
        return jdbc.query("""
                        SELECT stat_day, SUM(impressions), SUM(clicks), SUM(conversions) FROM rec_placement_stats
                        WHERE stat_day >= ? GROUP BY stat_day ORDER BY stat_day""",
                (rs, i) -> new DayRow(rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                Date.valueOf(from));
    }

    // ------------------------------------------------------------------ Tổng quan mô hình + dọn dẹp

    public long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    public Instant lastInteractionAt() {
        Timestamp t = jdbc.queryForObject("SELECT MAX(occurred_at) FROM rec_interactions", Timestamp.class);
        return t == null ? null : t.toInstant();
    }

    public int prune(Instant before) {
        int n = jdbc.update("DELETE FROM rec_interactions WHERE occurred_at < ?", Timestamp.from(before));
        n += jdbc.update("DELETE FROM rec_clicks WHERE clicked_at < ?", Timestamp.from(before));
        n += jdbc.update("DELETE FROM rec_daily WHERE stat_day < ?",
                Date.valueOf(day(before)));
        return n;
    }
}
