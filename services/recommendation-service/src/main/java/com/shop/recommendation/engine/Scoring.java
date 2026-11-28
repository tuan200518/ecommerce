package com.shop.recommendation.engine;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Các công thức chấm điểm của bộ gợi ý – hàm thuần, không phụ thuộc CSDL (dễ kiểm thử và giải thích).
 * <ul>
 *   <li>Lọc cộng tác item-item: độ tương đồng cosine trên số lần đồng xuất hiện, có hệ số tin cậy
 *       để một cặp hiếm gặp không lấn át cặp có nhiều bằng chứng.</li>
 *   <li>Dựa trên nội dung: cùng danh mục, giá tương đương, tên giống nhau (cho sản phẩm mới chưa có hành vi).</li>
 *   <li>Suy giảm theo thời gian (half-life) cho độ thịnh hành và hồ sơ sở thích.</li>
 *   <li>Đa dạng hóa kết quả: giới hạn số sản phẩm cùng danh mục ở đầu danh sách.</li>
 * </ul>
 */
public final class Scoring {

    /** Số bằng chứng để độ tin cậy đạt 50%: weight / (weight + SHRINKAGE). */
    public static final double SHRINKAGE = 2.0;
    /** Điểm đánh giá trung bình giả định (Bayes) khi sản phẩm có ít đánh giá. */
    public static final double PRIOR_RATING = 3.5;
    public static final double PRIOR_REVIEWS = 5.0;

    private Scoring() {
    }

    /**
     * Cosine giữa hai sản phẩm theo số lần đồng xuất hiện, nhân hệ số tin cậy.
     *
     * @param together số đơn (hoặc phiên xem) chứa cả hai
     * @param countA   số đơn/phiên chứa A
     * @param countB   số đơn/phiên chứa B
     */
    public static double similarity(double together, long countA, long countB) {
        if (together <= 0 || countA <= 0 || countB <= 0) {
            return 0;
        }
        double cosine = Math.min(1.0, together / Math.sqrt((double) countA * countB));
        return cosine * (together / (together + SHRINKAGE));
    }

    /** 1 khi cùng giá, giảm dần về 0 khi một bên đắt gấp 4 lần bên kia. */
    public static double priceSimilarity(BigDecimal a, BigDecimal b) {
        if (a == null || b == null || a.signum() <= 0 || b.signum() <= 0) {
            return 0;
        }
        double ratio = Math.abs(Math.log(a.doubleValue() / b.doubleValue()));
        return Math.max(0, 1 - ratio / Math.log(4));
    }

    /** Hệ số Jaccard trên tập từ khóa của tên sản phẩm. */
    public static double nameSimilarity(String a, String b) {
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0;
        }
        long common = ta.stream().filter(tb::contains).count();
        return (double) common / (ta.size() + tb.size() - common);
    }

    /** Điểm nội dung trong [0, 1]: danh mục 60%, giá 25%, tên 15%. */
    public static double contentSimilarity(Long categoryA, BigDecimal priceA, String nameA,
                                           Long categoryB, BigDecimal priceB, String nameB) {
        double category = categoryA != null && categoryA.equals(categoryB) ? 1 : 0;
        return 0.60 * category + 0.25 * priceSimilarity(priceA, priceB) + 0.15 * nameSimilarity(nameA, nameB);
    }

    /** Trọng số còn lại sau khoảng thời gian {@code ageMs} với chu kỳ bán rã {@code halfLifeMs}. */
    public static double decay(double ageMs, double halfLifeMs) {
        if (halfLifeMs <= 0) {
            return 1;
        }
        return Math.pow(0.5, Math.max(0, ageMs) / halfLifeMs);
    }

    /** Hệ số chất lượng trong [0.9, 1.1] từ điểm đánh giá trung bình Bayes (sản phẩm ít đánh giá ≈ 1). */
    public static double quality(long ratingSum, long ratingCount) {
        double bayes = (ratingSum + PRIOR_RATING * PRIOR_REVIEWS) / (ratingCount + PRIOR_REVIEWS);
        return 0.9 + 0.2 * (bayes - 1) / 4;
    }

    /** Điểm thịnh hành của một ngày: xem 1, thêm giỏ 3, mua 5. */
    public static double popularity(long views, long carts, long purchases) {
        return views + 3.0 * carts + 5.0 * purchases;
    }

    /** Chuẩn hóa không dấu, chữ thường, tách từ (bỏ từ 1 ký tự) – "Tai nghe Bluetooth" → [tai, nghe, bluetooth]. */
    public static Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        for (String t : normalize(text).split("[^a-z0-9]+")) {
            if (t.length() > 1) {
                out.add(t);
            }
        }
        return out;
    }

    public static String normalize(String text) {
        String s = Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return s.replace('đ', 'd');
    }

    /** Tên sản phẩm chứa mọi từ khóa của truy vấn tìm kiếm. */
    public static boolean matchesQuery(String name, String query) {
        Set<String> q = tokens(query);
        return !q.isEmpty() && tokens(name).containsAll(q);
    }

    /**
     * Sắp xếp lại để đa dạng: duyệt theo điểm giảm dần, mỗi danh mục tối đa {@code perCategory} món ở lượt đầu;
     * các món bị hoãn được nối vào cuối nếu chưa đủ {@code limit}.
     */
    public static <T> List<T> diversify(List<T> ranked, Function<T, Long> category, int perCategory, int limit) {
        List<T> picked = new ArrayList<>();
        List<T> deferred = new ArrayList<>();
        Map<Long, Integer> used = new HashMap<>();
        for (T item : ranked) {
            if (picked.size() >= limit) {
                break;
            }
            Long c = category.apply(item);
            int n = used.getOrDefault(c, 0);
            if (c != null && n >= perCategory) {
                deferred.add(item);
            } else {
                used.put(c, n + 1);
                picked.add(item);
            }
        }
        for (T item : deferred) {
            if (picked.size() >= limit) {
                break;
            }
            picked.add(item);
        }
        return picked;
    }
}
