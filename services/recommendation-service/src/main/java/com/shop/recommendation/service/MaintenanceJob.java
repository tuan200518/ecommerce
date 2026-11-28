package com.shop.recommendation.service;

import com.shop.recommendation.RecommendationProperties;
import com.shop.recommendation.store.RecommendationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Xóa hành vi, lượt bấm và thống kê ngày cũ hơn {@code shop.recommendation.interaction-retention}. */
@Component
@ConditionalOnProperty(prefix = "shop.recommendation", name = "jobs-enabled", havingValue = "true", matchIfMissing = true)
public class MaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceJob.class);

    private final RecommendationStore store;
    private final RecommendationProperties props;

    public MaintenanceJob(RecommendationStore store, RecommendationProperties props) {
        this.store = store;
        this.props = props;
    }

    @Scheduled(cron = "${shop.recommendation.prune-cron:0 30 3 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void prune() {
        int removed = store.prune(Instant.now().minus(props.interactionRetention()));
        if (removed > 0) {
            log.info("Đã dọn {} dòng dữ liệu gợi ý cũ", removed);
        }
    }
}
