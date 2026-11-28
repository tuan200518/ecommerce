package com.shop.recommendation.web;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class RecommendationDtos {

    /** @param reason lý do gợi ý, hiển thị cho khách (gợi ý có thể giải thích được) */
    public record RecommendedProduct(long productId, String name, BigDecimal price, String imageUrl, Long categoryId,
                                     String categoryName, double score, String reason) {
    }

    /**
     * @param placement vị trí hiển thị (similar, bought-together, for-you, trending, cart) – gửi lại khi khách bấm
     * @param strategy  thuật toán đã dùng (HYBRID, CO_PURCHASE, PERSONALIZED, TRENDING)
     */
    public record RecommendationList(String placement, String strategy, List<RecommendedProduct> items) {
    }

    public record ClickRequest(@NotBlank String placement, long productId) {
    }

    public record PlacementStats(String placement, String label, long requests, long impressions, long clicks,
                                 double ctr, long conversions, double conversionRate, double revenue) {
    }

    public record DayStats(LocalDate day, long impressions, long clicks, long conversions) {
    }

    public record ModelStats(long products, long activeProducts, long boughtPairs, long viewedPairs,
                             long interactions, long profiles, Instant lastLearnedAt) {
    }

    public record PairDto(long productId, String productName, long relatedId, String relatedName, double orders) {
    }

    public record AdminStats(int days, List<PlacementStats> placements, List<DayStats> daily, ModelStats model,
                             List<PairDto> topPairs) {
    }

    public record Seed(long productId, String name, String kind, double weight) {
    }

    public record CategoryAffinity(Long categoryId, String categoryName, double weight) {
    }

    /** Hồ sơ sở thích của một khách – để nhân viên hiểu vì sao khách nhận được các gợi ý. */
    public record UserProfile(long userId, List<Seed> seeds, List<CategoryAffinity> categories, List<String> searches,
                              RecommendationList forYou) {
    }

    private RecommendationDtos() {
    }
}
