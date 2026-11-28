package com.shop.analytics;

import com.shop.analytics.dashboard.DashboardSnapshotStore;
import com.shop.analytics.dashboard.DashboardState;
import com.shop.analytics.model.AnalyticsModels.Alert;
import com.shop.analytics.model.AnalyticsModels.MetricUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Dashboard khôi phục từ snapshot trong CSDL thay vì đọc lại toàn bộ topic analytics-events. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:analytics-snapshot;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.kafka.listener.auto-startup=false",
        "shop.outbox.relay-enabled=false",
        "shop.kafka.create-topics=false",
        "shop.analytics.streams.enabled=false",
        "shop.analytics.kafka-monitor.enabled=false"
})
class DashboardSnapshotTest {

    @Autowired
    DashboardState state;
    @Autowired
    DashboardSnapshotStore store;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        state.reset();
        jdbc.update("DELETE FROM dashboard_snapshot");
    }

    private void fill() {
        long minute = 1_700_000_040_000L;
        state.apply(new MetricUpdate("revenue_1m", "all", null, minute, minute + 60_000, 1_500_000, 3, null));
        state.apply(new MetricUpdate("revenue_total", "all", null, null, null, 9_000_000, 12, null));
        state.apply(new MetricUpdate("counter", "orders_created", null, null, null, 15, 15, null));
        state.apply(new MetricUpdate("orders_by_status", "CONFIRMED", null, null, null, 12, 12, null));
        state.apply(new MetricUpdate("product_sales", "7", "Tai nghe", null, null, 4_000_000, 8, null));
        state.apply(new MetricUpdate("stock", "7", "Tai nghe", null, null, 2, 1, Map.of("threshold", 5)));
        state.apply(new MetricUpdate("promotion_discount", "all", null, null, null, 250_000, 4, null));
        state.apply(new Alert("a-1", "LOW_STOCK", "WARNING", "Sắp hết hàng", "Tai nghe còn 2", minute, Map.of()));
        state.markApplied(0, 41);
        state.markApplied(2, 7);
    }

    @Test
    void snapshotRestoresEveryKpi_andResumesAfterTheLastAppliedOffset() {
        fill();
        state.resumePositions("topic-A");
        DashboardState.Kpis before = state.snapshot().kpis();
        store.save();

        state.reset();
        assertThat(state.snapshot().kpis().totalOrders()).isZero();

        store.load();
        DashboardState.Snapshot after = state.snapshot();
        assertThat(after.kpis()).isEqualTo(before);
        assertThat(after.kpis().revenue()).isEqualTo(9_000_000);
        assertThat(after.kpis().lowStockProducts()).isEqualTo(1);
        assertThat(after.revenuePerMinute()).singleElement().satisfies(p -> assertThat(p.value()).isEqualTo(1_500_000));
        assertThat(after.ordersByStatus()).containsEntry("CONFIRMED", 12L);
        assertThat(after.topProducts()).singleElement().satisfies(p -> assertThat(p.name()).isEqualTo("Tai nghe"));
        assertThat(after.alerts()).extracting(Alert::id).containsExactly("a-1");
        // Message đã có trong snapshot không bị khử trùng sai: cảnh báo cũ đọc lại không được tính là mới
        assertThat(state.apply(new Alert("a-1", "LOW_STOCK", "WARNING", "x", "x", 0, Map.of()))).isFalse();

        // Đọc tiếp NGAY SAU offset cuối cùng đã áp dụng; partition chưa có thì không có vị trí (đọc từ đầu)
        assertThat(state.resumePositions("topic-A")).containsExactlyInAnyOrderEntriesOf(Map.of(0, 42L, 2, 8L));
    }

    @Test
    void recreatedTopic_discardsTheSnapshot() {
        fill();
        state.resumePositions("topic-A");
        store.save();
        state.reset();
        store.load();

        assertThat(state.resumePositions("topic-B")).isEmpty();
        assertThat(state.snapshot().kpis().totalOrders()).isZero();
        // Không hỏi được broker → giữ nguyên trạng thái đang có
        state.markApplied(1, 3);
        assertThat(state.resumePositions(null)).containsEntry(1, 4L);
    }

    @Test
    void unchangedState_isNotRewritten_andCorruptSnapshotFallsBackToFullReplay() {
        fill();
        store.save();
        Object savedAt = jdbc.queryForObject("SELECT saved_at FROM dashboard_snapshot", Object.class);
        store.save();
        assertThat(jdbc.queryForObject("SELECT saved_at FROM dashboard_snapshot", Object.class)).isEqualTo(savedAt);

        jdbc.update("UPDATE dashboard_snapshot SET payload = '{hỏng'");
        store.load();
        assertThat(state.isEmpty()).isTrue();
        assertThat(state.resumePositions(null)).isEmpty();
    }
}
