package com.shop.recommendation.service;

import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.payload.ActivityEvents.ProductViewed;
import com.shop.common.event.payload.ActivityEvents.SearchPerformed;
import com.shop.common.event.payload.CartEvents.CartItemChanged;
import com.shop.common.event.payload.OrderEvents.OrderLine;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.event.payload.ProductEvents.ProductChanged;
import com.shop.common.event.payload.ReviewEvents.ReviewChanged;
import com.shop.recommendation.RecommendationProperties;
import com.shop.recommendation.store.RecommendationStore;
import com.shop.recommendation.store.RecommendationStore.CatalogItem;
import com.shop.recommendation.store.RecommendationStore.Click;
import com.shop.support.kafka.EventReader;
import com.shop.support.outbox.IdempotencyGuard;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * "Huấn luyện" mô hình gợi ý trực tuyến từ luồng sự kiện – mỗi sự kiện cập nhật ngay ma trận đồng xuất hiện,
 * độ phổ biến và hồ sơ sở thích, không cần job huấn luyện theo lô.
 * <p>
 * Thời điểm dùng cho mọi thống kê là {@code occurredAt} của sự kiện (không phải lúc xử lý) nên khi service mới
 * đọc lại lịch sử từ đầu topic, kết quả giống hệt như khi xử lý realtime.
 */
@Service
public class LearningService {

    static final String CONSUMER = "recommendation-service";

    public static final String VIEW = "VIEW";
    public static final String CART = "CART";
    public static final String BUY = "BUY";
    public static final String SEARCH = "SEARCH";
    public static final String BOUGHT = "BOUGHT";
    public static final String VIEWED = "VIEWED";

    /** Giới hạn số sản phẩm của một phiên được ghép cặp "cùng xem" (tránh phiên dài tạo quá nhiều cặp). */
    private static final int MAX_CO_VIEW = 10;

    private final RecommendationStore store;
    private final IdempotencyGuard guard;
    private final EventReader reader;
    private final RecommendationProperties props;
    private final MeterRegistry meters;

    public LearningService(RecommendationStore store, IdempotencyGuard guard, EventReader reader,
                           RecommendationProperties props, MeterRegistry meters) {
        this.store = store;
        this.guard = guard;
        this.reader = reader;
        this.props = props;
        this.meters = meters;
    }

    // ------------------------------------------------------------------ product-events

    @Transactional
    public void handleProductEvent(EventEnvelope env) {
        if (!env.is(EventTypes.PRODUCT_CREATED) && !env.is(EventTypes.PRODUCT_UPDATED)
                && !env.is(EventTypes.PRODUCT_DISCONTINUED)) {
            return;
        }
        ProductChanged p = reader.payload(env, ProductChanged.class);
        boolean active = p.active() && !env.is(EventTypes.PRODUCT_DISCONTINUED);
        store.upsertProduct(new CatalogItem(p.productId(), p.name(), p.categoryId(), p.categoryName(), p.price(),
                p.imageUrl(), active), at(env));
    }

    // ------------------------------------------------------------------ order-events

    @Transactional
    public void handleOrderEvent(EventEnvelope env) {
        if (!env.is(EventTypes.ORDER_CONFIRMED) || !guard.firstTime(env, CONSUMER)) {
            return;
        }
        OrderStatusChanged order = reader.payload(env, OrderStatusChanged.class);
        if (order.items() == null || order.items().isEmpty()) {
            return;
        }
        Instant at = at(env);
        // TreeMap: mọi transaction khóa dòng theo cùng thứ tự (bảng ngày → thống kê → cặp, productId tăng dần)
        // – các đơn chứa cùng cặp sản phẩm được xử lý song song trên nhiều partition sẽ không deadlock
        Map<Long, OrderLine> lines = new TreeMap<>();
        order.items().forEach(l -> lines.putIfAbsent(l.productId(), l));
        List<Long> ids = new ArrayList<>(lines.keySet());

        for (OrderLine line : lines.values()) {
            store.incrementDaily(line.productId(), RecommendationStore.day(at), "purchases", line.quantity());
        }
        for (Long id : ids) {
            store.incrementStat(id, "buyers", 1);
        }
        // Mỗi cặp sản phẩm trong cùng đơn: +1 theo cả hai chiều
        List<long[]> pairs = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                pairs.add(new long[]{ids.get(i), ids.get(j)});
                pairs.add(new long[]{ids.get(j), ids.get(i)});
            }
        }
        incrementPairs(pairs, BOUGHT);
        for (Long id : ids) {
            store.addInteraction(order.userId(), id, null, BUY, null, at);
        }
        attribute(order.userId(), lines, at);
    }

    /** Đơn hàng có sản phẩm khách đã bấm từ một gợi ý trong khung thời gian → tính chuyển đổi (lượt bấm cuối). */
    private void attribute(long userId, Map<Long, OrderLine> lines, Instant at) {
        Set<Long> seen = new HashSet<>();
        for (Click click : store.openClicks(userId, lines.keySet(), at.minus(props.attributionWindow()))) {
            if (!seen.add(click.productId())) {
                continue;
            }
            OrderLine line = lines.get(click.productId());
            BigDecimal revenue = line.unitPrice() == null ? BigDecimal.ZERO : line.lineTotal();
            store.markConverted(click.id());
            store.converted(RecommendationStore.day(at), click.placement(), revenue.doubleValue());
            meters.counter("shop.recommendation.conversions", "placement", click.placement()).increment();
        }
    }

    // ------------------------------------------------------------------ user-activity-events

    @Transactional
    public void handleActivityEvent(EventEnvelope env) {
        if (env.is(EventTypes.PRODUCT_VIEWED)) {
            if (guard.firstTime(env, CONSUMER)) {
                view(reader.payload(env, ProductViewed.class), at(env));
            }
        } else if (env.is(EventTypes.SEARCH_PERFORMED)) {
            if (guard.firstTime(env, CONSUMER)) {
                SearchPerformed s = reader.payload(env, SearchPerformed.class);
                String query = s.query() == null ? null : s.query().strip();
                if (s.userId() != null && (query != null && !query.isEmpty() || s.categoryId() != null)) {
                    store.addInteraction(s.userId(), null, s.categoryId(), SEARCH,
                            query == null || query.isEmpty() ? null : truncate(query, 255), at(env));
                }
            }
        }
    }

    private void view(ProductViewed v, Instant at) {
        store.incrementDaily(v.productId(), RecommendationStore.day(at), "views", 1);
        if (v.userId() == null) {
            return; // khách vãng lai: chỉ tính độ phổ biến
        }
        List<Long> session = store.recentViews(v.userId(), at.minus(props.coViewWindow()), at);
        store.addInteraction(v.userId(), v.productId(), v.categoryId(), VIEW, null, at);
        if (session.contains(v.productId())) {
            return; // xem lại trong cùng phiên: không tạo thêm cặp
        }
        store.incrementStat(v.productId(), "viewers", 1);
        List<long[]> pairs = new ArrayList<>();
        for (Long other : session.subList(0, Math.min(MAX_CO_VIEW, session.size()))) {
            pairs.add(new long[]{v.productId(), other});
            pairs.add(new long[]{other, v.productId()});
        }
        incrementPairs(pairs, VIEWED);
    }

    /** Cập nhật cặp theo thứ tự (productId, relatedId) tăng dần – thứ tự khóa nhất quán giữa các transaction. */
    private void incrementPairs(List<long[]> pairs, String kind) {
        pairs.sort(Comparator.<long[]>comparingLong(p -> p[0]).thenComparingLong(p -> p[1]));
        for (long[] p : pairs) {
            store.incrementPair(p[0], p[1], kind, 1);
        }
    }

    // ------------------------------------------------------------------ cart-events

    @Transactional
    public void handleCartEvent(EventEnvelope env) {
        if (!env.is(EventTypes.CART_ITEM_ADDED) || !guard.firstTime(env, CONSUMER)) {
            return;
        }
        CartItemChanged c = reader.payload(env, CartItemChanged.class);
        Instant at = at(env);
        store.incrementDaily(c.productId(), RecommendationStore.day(at), "carts", 1);
        store.addInteraction(c.userId(), c.productId(), null, CART, null, at);
    }

    // ------------------------------------------------------------------ review-events

    @Transactional
    public void handleReviewEvent(EventEnvelope env) {
        boolean submitted = env.is(EventTypes.REVIEW_SUBMITTED);
        if (!submitted && !env.is(EventTypes.REVIEW_VISIBILITY_CHANGED) || !guard.firstTime(env, CONSUMER)) {
            return;
        }
        ReviewChanged r = reader.payload(env, ReviewChanged.class);
        if (submitted) {
            if (r.visible()) {
                store.addRating(r.productId(), r.rating(), 1);
            }
        } else {
            int sign = r.visible() ? 1 : -1;
            store.addRating(r.productId(), (long) sign * r.rating(), sign);
        }
    }

    private static Instant at(EventEnvelope env) {
        return env.occurredAt() == null ? Instant.now() : env.occurredAt();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
