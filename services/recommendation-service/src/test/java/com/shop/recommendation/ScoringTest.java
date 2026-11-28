package com.shop.recommendation;

import com.shop.recommendation.engine.Scoring;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ScoringTest {

    @Test
    void similarity_isCosineDampedByEvidence() {
        // Một đơn duy nhất chứa cả hai sản phẩm hiếm → cosine = 1 nhưng độ tin cậy thấp
        double rare = Scoring.similarity(1, 1, 1);
        // 30 đơn chung trên 40 × 50 đơn → cosine ≈ 0.67, độ tin cậy cao
        double strong = Scoring.similarity(30, 40, 50);
        assertThat(rare).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(strong).isGreaterThan(rare);
        assertThat(Scoring.similarity(0, 10, 10)).isZero();
        assertThat(Scoring.similarity(5, 0, 10)).isZero();
    }

    @Test
    void contentSimilarity_prefersSameCategoryAndClosePrice() {
        BigDecimal p = new BigDecimal("1000000");
        double same = Scoring.contentSimilarity(1L, p, "Tai nghe Bluetooth Sony", 1L, new BigDecimal("1200000"),
                "Tai nghe chống ồn Sony");
        double otherCategory = Scoring.contentSimilarity(1L, p, "Tai nghe Bluetooth Sony", 2L, p, "Ốp lưng iPhone");
        double tooExpensive = Scoring.contentSimilarity(1L, p, "Tai nghe", 1L, new BigDecimal("8000000"), "Loa");
        assertThat(same).isGreaterThan(tooExpensive).isGreaterThan(otherCategory);
        assertThat(Scoring.priceSimilarity(p, p)).isEqualTo(1.0);
        assertThat(Scoring.priceSimilarity(p, new BigDecimal("4000000"))).isCloseTo(0, within(1e-9));
    }

    @Test
    void vietnameseText_isMatchedWithoutDiacritics() {
        assertThat(Scoring.tokens("Bàn phím cơ Đen")).containsExactly("ban", "phim", "co", "den");
        assertThat(Scoring.matchesQuery("Bàn phím cơ Keychron K2", "ban phim")).isTrue();
        assertThat(Scoring.matchesQuery("Bàn phím cơ Keychron K2", "chuột")).isFalse();
        assertThat(Scoring.matchesQuery("Bất kỳ", "  ")).isFalse();
    }

    @Test
    void decayAndQuality() {
        assertThat(Scoring.decay(0, 1000)).isEqualTo(1.0);
        assertThat(Scoring.decay(1000, 1000)).isEqualTo(0.5);
        assertThat(Scoring.decay(3000, 1000)).isEqualTo(0.125);
        // Không có đánh giá → gần trung tính; nhiều đánh giá 5★ → cao hơn; nhiều 1★ → thấp hơn
        double none = Scoring.quality(0, 0);
        assertThat(Scoring.quality(500, 100)).isGreaterThan(none);
        assertThat(Scoring.quality(100, 100)).isLessThan(none);
        assertThat(Scoring.quality(500, 100)).isLessThanOrEqualTo(1.1);
    }

    @Test
    void diversify_limitsOneCategoryAtTheTop_thenBackfills() {
        record Item(String id, Long category) {
        }
        List<Item> ranked = List.of(new Item("a1", 1L), new Item("a2", 1L), new Item("a3", 1L), new Item("a4", 1L),
                new Item("b1", 2L), new Item("c1", 3L));
        assertThat(Scoring.diversify(ranked, Item::category, 2, 5)).extracting(Item::id)
                .containsExactly("a1", "a2", "b1", "c1", "a3");
        assertThat(Scoring.diversify(ranked, Item::category, 2, 3)).extracting(Item::id)
                .containsExactly("a1", "a2", "b1");
    }
}
