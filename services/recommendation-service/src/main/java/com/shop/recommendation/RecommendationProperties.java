package com.shop.recommendation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tham số của bộ gợi ý (shop.recommendation.*).
 *
 * @param coViewWindow          hai sản phẩm được cùng một khách xem trong khoảng này → "khách cũng xem"
 * @param profileWindow         chỉ dùng hành vi trong khoảng này để dựng hồ sơ sở thích
 * @param profileHalfLife       hành vi cũ hơn một chu kỳ bán rã có trọng số giảm một nửa
 * @param trendingDays          số ngày gần nhất tính độ thịnh hành
 * @param trendingHalfLifeDays  chu kỳ bán rã (ngày) của độ thịnh hành
 * @param attributionWindow     đơn hàng trong khoảng này sau khi bấm gợi ý được tính là chuyển đổi
 * @param cacheTtl              thời gian lưu đệm danh mục + bảng xếp hạng thịnh hành
 * @param interactionRetention  xóa hành vi / thống kê cũ hơn khoảng này
 */
@ConfigurationProperties(prefix = "shop.recommendation")
public record RecommendationProperties(Duration coViewWindow, Duration profileWindow, Duration profileHalfLife,
                                       Integer trendingDays, Double trendingHalfLifeDays, Duration attributionWindow,
                                       Duration cacheTtl, Duration interactionRetention) {

    public RecommendationProperties {
        coViewWindow = coViewWindow == null ? Duration.ofMinutes(30) : coViewWindow;
        profileWindow = profileWindow == null ? Duration.ofDays(30) : profileWindow;
        profileHalfLife = profileHalfLife == null ? Duration.ofDays(3) : profileHalfLife;
        trendingDays = trendingDays == null ? 7 : trendingDays;
        trendingHalfLifeDays = trendingHalfLifeDays == null ? 2.0 : trendingHalfLifeDays;
        attributionWindow = attributionWindow == null ? Duration.ofHours(24) : attributionWindow;
        cacheTtl = cacheTtl == null ? Duration.ofSeconds(30) : cacheTtl;
        interactionRetention = interactionRetention == null ? Duration.ofDays(90) : interactionRetention;
    }
}
