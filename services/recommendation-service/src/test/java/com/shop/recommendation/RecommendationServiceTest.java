package com.shop.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.payload.ActivityEvents.ProductViewed;
import com.shop.common.event.payload.ActivityEvents.SearchPerformed;
import com.shop.common.event.payload.CartEvents.CartItemChanged;
import com.shop.common.event.payload.OrderEvents.OrderLine;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.event.payload.ProductEvents.ProductChanged;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.recommendation.service.LearningService;
import com.shop.recommendation.service.RecommendationService;
import com.shop.recommendation.web.RecommendationDtos.AdminStats;
import com.shop.recommendation.web.RecommendationDtos.PlacementStats;
import com.shop.recommendation.web.RecommendationDtos.RecommendationList;
import com.shop.recommendation.web.RecommendationDtos.RecommendedProduct;
import com.shop.recommendation.web.RecommendationDtos.UserProfile;
import com.shop.support.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Học từ sự kiện rồi gợi ý: mua cùng, tương tự, cá nhân hóa, thịnh hành, đo CTR + chuyển đổi. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:rec;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.kafka.listener.auto-startup=false",
        "shop.outbox.relay-enabled=false",
        "shop.kafka.create-topics=false",
        "shop.recommendation.cache-ttl=0s",
        "shop.recommendation.jobs-enabled=false"
})
class RecommendationServiceTest {

    private static final AuthenticatedUser ANALYST = new AuthenticatedUser(2, "analyst@shop.local", Set.of(Roles.ANALYST));
    private static final long AUDIO = 10;
    private static final long PHONE = 20;
    private static final AtomicLong ORDER_IDS = new AtomicLong(1000);

    @Autowired
    LearningService learning;
    @Autowired
    RecommendationService recs;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seedCatalog() {
        for (String t : List.of("rec_products", "rec_product_stats", "rec_pairs", "rec_daily", "rec_interactions",
                "rec_placement_stats", "rec_clicks", "processed_events")) {
            jdbc.update("DELETE FROM " + t);
        }
        product(1, "Tai nghe Bluetooth Sony", AUDIO, "Âm thanh", 2_000_000);
        product(2, "Tai nghe chống ồn Bose", AUDIO, "Âm thanh", 2_500_000);
        product(3, "Loa mini JBL", AUDIO, "Âm thanh", 1_500_000);
        product(4, "Ốp lưng iPhone", PHONE, "Phụ kiện điện thoại", 200_000);
        product(5, "Cáp sạc nhanh USB-C", PHONE, "Phụ kiện điện thoại", 150_000);
        product(6, "Sạc dự phòng 10000mAh", PHONE, "Phụ kiện điện thoại", 500_000);
        recs.invalidate();
    }

    private EventEnvelope env(String type, String key, Object payload) {
        return EventEnvelope.create(mapper, type, "test", key, payload);
    }

    private void product(long id, String name, long category, String categoryName, long price) {
        learning.handleProductEvent(env(EventTypes.PRODUCT_CREATED, String.valueOf(id), new ProductChanged(id,
                "SKU-" + id, name, BigDecimal.valueOf(price), category, categoryName, null, true, 10)));
    }

    private void paid(long userId, long... productIds) {
        long orderId = ORDER_IDS.incrementAndGet();
        List<OrderLine> lines = Arrays.stream(productIds)
                .mapToObj(p -> new OrderLine(p, "SP " + p, 1, BigDecimal.valueOf(100_000))).toList();
        learning.handleOrderEvent(env(EventTypes.ORDER_CONFIRMED, String.valueOf(orderId),
                new OrderStatusChanged(orderId, userId, "AWAITING_PAYMENT", "CONFIRMED", BigDecimal.ONE, null, lines, "addr")));
    }

    private void view(Long userId, long productId) {
        learning.handleActivityEvent(env(EventTypes.PRODUCT_VIEWED, String.valueOf(productId),
                new ProductViewed(userId, productId, "SP " + productId, null, BigDecimal.ONE)));
    }

    private static AuthenticatedUser customer(long id) {
        return new AuthenticatedUser(id, "c" + id + "@shop.local", Set.of(Roles.CUSTOMER));
    }

    private static List<Long> ids(RecommendationList list) {
        return list.items().stream().map(RecommendedProduct::productId).toList();
    }

    @Test
    void boughtTogether_ranksByCoPurchase_andSkipsDiscontinuedProducts() {
        paid(101, 1, 5);
        paid(102, 1, 5);
        paid(103, 1, 5, 3);
        paid(104, 1, 3);
        paid(105, 1, 4);

        RecommendationList list = recs.boughtTogether(1, 4);
        assertThat(list.strategy()).isEqualTo("CO_PURCHASE");
        assertThat(ids(list)).containsExactly(5L, 3L, 4L);
        assertThat(list.items().get(0).reason()).isEqualTo("Thường được mua cùng · 3 đơn");
        // Quan hệ đối xứng
        assertThat(ids(recs.boughtTogether(5, 4))).startsWith(1L);

        learning.handleProductEvent(env(EventTypes.PRODUCT_DISCONTINUED, "5", new ProductChanged(5, "SKU-5",
                "Cáp sạc nhanh USB-C", BigDecimal.ONE, PHONE, "Phụ kiện điện thoại", null, false, null)));
        assertThat(ids(recs.boughtTogether(1, 4))).containsExactly(3L, 4L);
    }

    @Test
    void replayedEvent_isLearnedOnlyOnce() {
        EventEnvelope order = env(EventTypes.ORDER_CONFIRMED, "1", new OrderStatusChanged(1, 101, "X", "CONFIRMED",
                BigDecimal.ONE, null, List.of(new OrderLine(1, "a", 1, BigDecimal.TEN), new OrderLine(2, "b", 1, BigDecimal.TEN)),
                "addr"));
        learning.handleOrderEvent(order);
        learning.handleOrderEvent(order);
        assertThat(jdbc.queryForObject("SELECT weight FROM rec_pairs WHERE product_id = 1 AND related_id = 2 AND kind = 'BOUGHT'",
                Double.class)).isEqualTo(1.0);
        assertThat(jdbc.queryForObject("SELECT buyers FROM rec_product_stats WHERE product_id = 1", Long.class)).isEqualTo(1);
    }

    @Test
    void similar_blendsCoViewsWithContent_andWorksForProductsWithoutHistory() {
        // Nhiều khách xem "Tai nghe Sony" rồi xem "Sạc dự phòng" trong cùng phiên
        for (long u = 201; u <= 205; u++) {
            view(u, 1);
            view(u, 6);
        }
        view(206L, 1);
        view(206L, 1); // xem lại trong cùng phiên không tạo cặp mới

        RecommendationList similar = recs.similar(1, 5);
        assertThat(similar.strategy()).isEqualTo("HYBRID");
        assertThat(ids(similar)).contains(6L, 2L, 3L).doesNotContain(1L);
        RecommendedProduct sac = similar.items().stream().filter(i -> i.productId() == 6).findFirst().orElseThrow();
        assertThat(sac.reason()).isEqualTo("Khách xem sản phẩm này cũng xem");
        RecommendedProduct bose = similar.items().stream().filter(i -> i.productId() == 2).findFirst().orElseThrow();
        assertThat(bose.reason()).isEqualTo("Cùng danh mục Âm thanh, giá tương đương");
        assertThat(jdbc.queryForObject("SELECT viewers FROM rec_product_stats WHERE product_id = 1", Long.class)).isEqualTo(6);

        // Sản phẩm mới chưa có hành vi nào: vẫn có gợi ý theo nội dung (cold start)
        product(7, "Tai nghe gaming Logitech", AUDIO, "Âm thanh", 1_800_000);
        recs.invalidate();
        assertThat(ids(recs.similar(7, 3))).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void forYou_isPersonalized_explained_andDiverse() {
        // Hành vi chung: ai xem tai nghe Sony cũng hay xem loa JBL; ai mua tai nghe Sony hay mua cáp sạc
        for (long u = 301; u <= 304; u++) {
            view(u, 1);
            view(u, 3);
            paid(u, 1, 5);
        }
        view(null, 6); // khách vãng lai → chỉ tăng độ phổ biến

        // Khách vãng lai / khách mới → thịnh hành
        assertThat(recs.forYou(Optional.empty(), 6).strategy()).isEqualTo("TRENDING");
        assertThat(recs.forYou(Optional.of(customer(399)), 6).strategy()).isEqualTo("TRENDING");

        AuthenticatedUser me = customer(350);
        view(350L, 1);
        learning.handleCartEvent(env(EventTypes.CART_ITEM_ADDED, "350",
                new CartItemChanged(350, 1, "Tai nghe Bluetooth Sony", 1, BigDecimal.ONE)));
        learning.handleActivityEvent(env(EventTypes.SEARCH_PERFORMED, "350",
                new SearchPerformed(350L, "sạc dự phòng", null, 1)));

        RecommendationList list = recs.forYou(Optional.of(me), 5);
        assertThat(list.strategy()).isEqualTo("PERSONALIZED");
        assertThat(list.items()).hasSize(5);
        assertThat(ids(list)).doesNotContain(1L).contains(3L, 5L, 6L);
        assertThat(list.items()).anySatisfy(i ->
                assertThat(i.reason()).isEqualTo("Vì bạn đã thêm Tai nghe Bluetooth Sony vào giỏ"));
        assertThat(list.items()).anySatisfy(i -> assertThat(i.reason()).isEqualTo("Vì bạn đã tìm “sạc dự phòng”"));

        UserProfile profile = recs.explain(ANALYST, 350, 5);
        assertThat(profile.seeds()).singleElement().satisfies(s -> {
            assertThat(s.productId()).isEqualTo(1);
            assertThat(s.kind()).isEqualTo("CART");
        });
        assertThat(profile.categories()).first().satisfies(c -> assertThat(c.categoryName()).isEqualTo("Âm thanh"));
        assertThat(profile.searches()).containsExactly("sạc dự phòng");
        assertThatThrownBy(() -> recs.explain(customer(350), 350, 5)).isInstanceOf(ApiException.class);
    }

    @Test
    void forYou_isNeverEmpty_evenWhenTheCustomerTouchedTheWholeCatalog() {
        for (long p = 1; p <= 6; p++) {
            view(360L, p);
        }
        paid(360, 1, 2);

        RecommendationList list = recs.forYou(Optional.of(customer(360)), 4);
        assertThat(list.items()).hasSize(4);
        // Không còn sản phẩm nào chưa tương tác → không gọi là "cá nhân hóa"
        assertThat(list.strategy()).isEqualTo("TRENDING");
        assertThat(list.items()).anySatisfy(i -> assertThat(i.reason()).isEqualTo("Bạn từng mua – mua lại?"));
    }

    @Test
    void trending_andCart_fallBackGracefully() {
        for (int i = 0; i < 4; i++) {
            view(null, 4);
        }
        view(null, 2);
        paid(401, 2, 6);

        RecommendationList trending = recs.trending(3, null);
        // Điểm ngày = xem + 3×giỏ + 5×mua: #2 = 1 + 5 = 6, #6 = 5, #4 = 4 lượt xem
        assertThat(ids(trending)).containsExactly(2L, 6L, 4L);
        assertThat(trending.items()).allSatisfy(i -> assertThat(i.reason()).isEqualTo("Đang thịnh hành"));
        assertThat(ids(recs.trending(5, PHONE))).containsExactly(6L, 4L);

        RecommendationList cart = recs.cart(List.of(2L), 3);
        assertThat(ids(cart)).first().isEqualTo(6L);
        assertThat(cart.items().get(0).reason()).isEqualTo("Thường được mua cùng Tai nghe chống ồn Bose");
        assertThat(ids(cart)).hasSize(3).doesNotContain(2L);
    }

    @Test
    void clicksAndAttributedOrders_areMeasuredPerPlacement() {
        paid(501, 1, 5);
        AuthenticatedUser buyer = customer(502);
        RecommendationList list = recs.boughtTogether(1, 4);
        assertThat(ids(list)).containsExactly(5L);

        recs.click(Optional.of(buyer), list.placement(), 5);
        recs.click(Optional.empty(), "for-you", 3);
        assertThatThrownBy(() -> recs.click(Optional.empty(), "khong-ton-tai", 3)).isInstanceOf(ApiException.class);
        paid(502, 5, 2); // mua sản phẩm vừa bấm từ gợi ý
        paid(502, 5);    // đơn thứ hai không được tính trùng cho cùng một lượt bấm

        AdminStats stats = recs.stats(ANALYST, 7);
        PlacementStats bt = stats.placements().stream().filter(p -> p.placement().equals("bought-together"))
                .findFirst().orElseThrow();
        assertThat(bt.requests()).isEqualTo(1);
        assertThat(bt.impressions()).isEqualTo(1);
        assertThat(bt.clicks()).isEqualTo(1);
        assertThat(bt.ctr()).isEqualTo(100.0);
        assertThat(bt.conversions()).isEqualTo(1);
        assertThat(bt.revenue()).isEqualTo(100_000.0);
        assertThat(stats.placements()).filteredOn(p -> p.placement().equals("for-you"))
                .singleElement().satisfies(p -> assertThat(p.clicks()).isEqualTo(1));
        assertThat(stats.model().boughtPairs()).isEqualTo(2);
        assertThat(stats.topPairs()).first().satisfies(p -> {
            assertThat(p.productName()).isEqualTo("Tai nghe Bluetooth Sony");
            assertThat(p.relatedName()).isEqualTo("Cáp sạc nhanh USB-C");
        });
        assertThatThrownBy(() -> recs.stats(buyer, 7)).isInstanceOf(ApiException.class);
    }
}
