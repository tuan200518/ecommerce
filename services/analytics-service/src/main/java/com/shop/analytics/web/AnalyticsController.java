package com.shop.analytics.web;

import com.shop.analytics.dashboard.AnalyticsRepository;
import com.shop.analytics.dashboard.DashboardBroadcaster;
import com.shop.analytics.dashboard.DashboardState;
import com.shop.analytics.dashboard.DashboardState.Snapshot;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.support.web.Access;
import com.shop.support.web.ApiException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private static final String[] VIEWERS = {Roles.ADMIN, Roles.ANALYST, Roles.PRODUCT_MANAGER, Roles.WAREHOUSE_MANAGER};

    private final DashboardState state;
    private final DashboardBroadcaster broadcaster;
    private final AnalyticsRepository repository;

    public AnalyticsController(DashboardState state, DashboardBroadcaster broadcaster, AnalyticsRepository repository) {
        this.state = state;
        this.broadcaster = broadcaster;
        this.repository = repository;
    }

    @GetMapping("/snapshot")
    public Snapshot snapshot(AuthenticatedUser user) {
        Access.requireAnyRole(user, VIEWERS);
        return state.snapshot();
    }

    /** Luồng realtime cho Dashboard (event "snapshot" mỗi giây khi có thay đổi, "alert" ngay lập tức). */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(AuthenticatedUser user) {
        Access.requireAnyRole(user, VIEWERS);
        return broadcaster.connect();
    }

    /** Báo cáo doanh thu từ bảng tổng hợp sales_aggregates. */
    @GetMapping("/reports/sales")
    public List<Map<String, Object>> sales(AuthenticatedUser user,
                                           @RequestParam(defaultValue = "1h") String window,
                                           @RequestParam(defaultValue = "24") int hours) {
        Access.requireAnyRole(user, Roles.ADMIN, Roles.ANALYST);
        if (!Set.of("1m", "5m", "1h").contains(window)) {
            throw ApiException.badRequest("window phải là 1m, 5m hoặc 1h");
        }
        Instant to = Instant.now().plus(Duration.ofHours(1));
        Instant from = Instant.now().minus(Duration.ofHours(Math.min(Math.max(hours, 1), 24 * 31)));
        return repository.salesReport(window, from, to);
    }

    @GetMapping("/reports/products")
    public List<Map<String, Object>> products(AuthenticatedUser user, @RequestParam(defaultValue = "20") int limit) {
        Access.requireAnyRole(user, VIEWERS);
        return repository.productReport(Math.min(Math.max(limit, 1), 200));
    }

    @GetMapping("/reports/alerts")
    public List<Map<String, Object>> alerts(AuthenticatedUser user, @RequestParam(defaultValue = "100") int limit) {
        Access.requireAnyRole(user, VIEWERS);
        return repository.alertHistory(Math.min(Math.max(limit, 1), 500));
    }
}
