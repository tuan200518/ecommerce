package com.shop.promotion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.LoyaltyEvents.PointsRedeemed;
import com.shop.common.event.payload.OrderEvents.OrderCreated;
import com.shop.common.event.payload.OrderEvents.OrderLine;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.event.payload.ProductEvents.ProductChanged;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.promotion.domain.FlashSaleItemRepository;
import com.shop.promotion.service.PromotionAdminService;
import com.shop.promotion.service.PromotionService;
import com.shop.promotion.web.PromotionDtos.FlashSaleDto;
import com.shop.promotion.web.PromotionDtos.FlashSaleItemRequest;
import com.shop.promotion.web.PromotionDtos.FlashSaleRequest;
import com.shop.promotion.web.PromotionDtos.QuoteDto;
import com.shop.promotion.web.PromotionDtos.QuoteItem;
import com.shop.promotion.web.PromotionDtos.QuoteRequest;
import com.shop.promotion.web.PromotionDtos.VoucherRequest;
import com.shop.support.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:promotion;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.listener.auto-startup=false",
        "shop.outbox.relay-enabled=false",
        "shop.kafka.create-topics=false",
        "shop.promotion.seed-enabled=false"
})
class PromotionServiceTest {

    private static final AuthenticatedUser PM = new AuthenticatedUser(3, "pm@shop.local", Set.of(Roles.PRODUCT_MANAGER));
    private static final AuthenticatedUser ANALYST = new AuthenticatedUser(4, "analyst@shop.local", Set.of(Roles.ANALYST));
    private static final AtomicLong ORDER_IDS = new AtomicLong(1000);
    private static final AtomicLong PRODUCT_IDS = new AtomicLong(100);

    @Autowired
    PromotionService service;
    @Autowired
    PromotionAdminService admin;
    @Autowired
    FlashSaleItemRepository flashItems;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    private long laptop;
    private long mouse;

    @BeforeEach
    void catalog() {
        jdbc.update("DELETE FROM outbox_events");
        laptop = product("Laptop", "20000000");
        mouse = product("Chuột", "200000");
    }

    private static AuthenticatedUser customer(long id) {
        return new AuthenticatedUser(id, "c" + id + "@shop.local", Set.of(Roles.CUSTOMER));
    }

    private long product(String name, String price) {
        long id = PRODUCT_IDS.incrementAndGet();
        service.handleProductEvent(EventEnvelope.create(mapper, EventTypes.PRODUCT_CREATED, "test", String.valueOf(id),
                new ProductChanged(id, "SKU-" + id, name + " " + id, new BigDecimal(price), 1L, "Cat", null, true, 10)));
        return id;
    }

    private FlashSaleDto flashSale(long productId, String salePrice, int quota, int perUser) {
        Instant now = Instant.now();
        return admin.createFlashSale(PM, new FlashSaleRequest("Flash " + productId, null, now.minusSeconds(60),
                now.plus(Duration.ofHours(2)), List.of(new FlashSaleItemRequest(productId, new BigDecimal(salePrice),
                quota, perUser))));
    }

    private String voucher(String type, String value, String min, Integer usageLimit, int perUser) {
        String code = "V" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Instant now = Instant.now();
        admin.createVoucher(PM, new VoucherRequest(code, "Test", type, value == null ? null : new BigDecimal(value),
                null, min == null ? null : new BigDecimal(min), now.minusSeconds(60), now.plus(Duration.ofDays(1)),
                usageLimit, perUser));
        return code;
    }

    /** Đặt đơn (OrderCreated) và trả về sự kiện promotion-events mà service phát ra cho đơn đó. */
    private JsonNode order(long userId, long productId, String unitPrice, int qty, String voucherCode, String fee) {
        return order(userId, productId, unitPrice, qty, voucherCode, fee, Instant.now());
    }

    private JsonNode order(long userId, long productId, String unitPrice, int qty, String voucherCode, String fee,
                           Instant occurredAt) {
        long orderId = ORDER_IDS.incrementAndGet();
        BigDecimal price = new BigDecimal(unitPrice);
        OrderCreated payload = new OrderCreated(orderId, userId,
                List.of(new OrderLine(productId, "SP " + productId, qty, price)),
                price.multiply(BigDecimal.valueOf(qty)).add(new BigDecimal(fee)), "MOCK_CARD", occurredAt, "addr",
                new BigDecimal(fee), voucherCode);
        service.handleOrderEvent(new EventEnvelope(UUID.randomUUID().toString(), EventTypes.ORDER_CREATED, 1,
                occurredAt, "order-service", String.valueOf(orderId), mapper.valueToTree(payload), "corr-" + orderId));
        List<String> rows = jdbc.queryForList("""
                SELECT payload FROM outbox_events WHERE topic = ? AND message_key = ? ORDER BY id DESC""",
                String.class, Topics.PROMOTION_EVENTS, String.valueOf(orderId));
        if (rows.isEmpty()) {
            return null;
        }
        try {
            JsonNode env = mapper.readTree(rows.get(0));
            ((com.fasterxml.jackson.databind.node.ObjectNode) env.get("payload")).put("_type", env.get("eventType").asText());
            return env.get("payload");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void cancel(long orderId, long userId) {
        service.handleOrderEvent(EventEnvelope.create(mapper, EventTypes.ORDER_CANCELLED, "order-service",
                String.valueOf(orderId), new OrderStatusChanged(orderId, userId, "PENDING", "CANCELLED",
                        BigDecimal.ZERO, "test", List.of(), "addr")));
    }

    private int sold(long productId) {
        return flashItems.findAll().stream().filter(i -> i.getProductId() == productId).mapToInt(i -> i.getSold()).sum();
    }

    @Test
    void everyOrderGetsAPricingDecision_evenWithoutPromotions() {
        JsonNode r = order(501, mouse, "200000", 1, null, "30000");
        assertThat(r.get("_type").asText()).isEqualTo(EventTypes.PROMOTION_APPLIED);
        assertThat(r.get("totalDiscount").decimalValue()).isEqualByComparingTo("0");
        assertThat(r.get("summary").asText()).isEqualTo("Không có ưu đãi");
    }

    @Test
    void flashSale_reservesQuota_enforcesPerUserLimit_andReleasesOnCancel() {
        flashSale(laptop, "15000000", 3, 2);

        JsonNode first = order(601, laptop, "20000000", 2, null, "0");
        assertThat(first.get("_type").asText()).isEqualTo(EventTypes.PROMOTION_APPLIED);
        assertThat(first.get("itemDiscount").decimalValue()).isEqualByComparingTo("10000000");
        assertThat(first.get("lines").get(0).get("salePrice").decimalValue()).isEqualByComparingTo("15000000");
        assertThat(sold(laptop)).isEqualTo(2);

        // Cùng khách mua thêm → vượt giới hạn 2 sản phẩm/khách
        JsonNode again = order(601, laptop, "20000000", 1, null, "0");
        assertThat(again.get("_type").asText()).isEqualTo(EventTypes.PROMOTION_REJECTED);
        assertThat(again.get("reason").asText()).contains("tối đa 2");

        // Khách khác: còn 1 suất → mua 2 bị từ chối, mua 1 được
        assertThat(order(602, laptop, "20000000", 2, null, "0").get("reason").asText()).contains("chỉ còn 1 suất");
        assertThat(order(602, laptop, "20000000", 1, null, "0").get("_type").asText())
                .isEqualTo(EventTypes.PROMOTION_APPLIED);
        assertThat(sold(laptop)).isEqualTo(3);
        assertThat(order(603, laptop, "20000000", 1, null, "0").get("reason").asText()).contains("hết suất");

        // Hủy đơn đầu → trả 2 suất và 2 lượt của khách 601 (gửi lại sự kiện hủy không trả thêm)
        cancel(first.get("orderId").asLong(), 601);
        cancel(first.get("orderId").asLong(), 601);
        assertThat(sold(laptop)).isEqualTo(1);
        assertThat(order(601, laptop, "20000000", 2, null, "0").get("_type").asText())
                .isEqualTo(EventTypes.PROMOTION_APPLIED);
    }

    @Test
    void voucher_perUserLimit_totalLimit_andRestoreAfterCancel() {
        String code = voucher("FIXED", "50000", "100000", 2, 1);

        JsonNode used = order(701, mouse, "200000", 1, code.toLowerCase(), "30000");
        assertThat(used.get("voucherCode").asText()).isEqualTo(code);
        assertThat(used.get("voucherDiscount").decimalValue()).isEqualByComparingTo("50000");

        assertThat(order(701, mouse, "200000", 1, code, "30000").get("reason").asText())
                .contains("hết số lượt");
        assertThat(order(702, mouse, "200000", 1, code, "30000").get("_type").asText())
                .isEqualTo(EventTypes.PROMOTION_APPLIED);
        // Đã dùng hết 2 lượt tổng
        assertThat(order(703, mouse, "200000", 1, code, "30000").get("reason").asText()).contains("hết lượt");

        cancel(used.get("orderId").asLong(), 701);
        assertThat(order(703, mouse, "200000", 1, code, "30000").get("_type").asText())
                .isEqualTo(EventTypes.PROMOTION_APPLIED);

        // Đơn dưới mức tối thiểu và mã không tồn tại
        String big = voucher("PERCENT", "10", "1000000", null, 5);
        assertThat(order(704, mouse, "200000", 1, big, "30000").get("reason").asText()).contains("đơn từ 1.000.000");
        assertThat(order(704, mouse, "200000", 1, "KHONGCO", "30000").get("reason").asText()).contains("không tồn tại");
    }

    @Test
    void freeShippingVoucher_discountsShippingFee_andIsNotConsumedWhenShippingIsAlreadyFree() {
        String code = voucher("FREE_SHIPPING", null, null, 1, 1);
        JsonNode free = order(801, laptop, "20000000", 1, code, "0");
        assertThat(free.get("_type").asText()).isEqualTo(EventTypes.PROMOTION_APPLIED);
        assertThat(free.get("voucherDiscount").decimalValue()).isEqualByComparingTo("0");
        // Lượt duy nhất vẫn còn → dùng được cho đơn có phí
        JsonNode paid = order(801, mouse, "200000", 1, code, "30000");
        assertThat(paid.get("voucherDiscount").decimalValue()).isEqualByComparingTo("30000");
    }

    @Test
    void oldOrderEvents_areIgnored() {
        assertThat(order(901, mouse, "200000", 1, null, "30000", Instant.now().minus(Duration.ofHours(3)))).isNull();
    }

    @Test
    void quote_showsSalePrices_andWarnings_withoutReserving() {
        flashSale(mouse, "150000", 10, 1);
        QuoteDto q = service.quote(customer(1001), new QuoteRequest(
                List.of(new QuoteItem(mouse, 1), new QuoteItem(laptop, 1)), "KHONGCO", new BigDecimal("0")));
        assertThat(q.itemDiscount()).isEqualByComparingTo("50000");
        assertThat(q.lines()).anySatisfy(l -> assertThat(l.flashSale()).startsWith("Flash"));
        assertThat(q.voucherValid()).isFalse();
        assertThat(q.warnings()).anyMatch(w -> w.contains("không tồn tại"));
        assertThat(sold(mouse)).isZero();

        QuoteDto tooMany = service.quote(customer(1001), new QuoteRequest(List.of(new QuoteItem(mouse, 2)), null, null));
        assertThat(tooMany.warnings()).anyMatch(w -> w.contains("tối đa 1"));

        assertThat(service.currentFlashSales()).anySatisfy(f -> {
            assertThat(f.status()).isEqualTo("ACTIVE");
            assertThat(f.items().get(0).discountPercent()).isEqualTo(25);
        });
    }

    @Test
    void loyaltyRedemption_issuesPersonalVoucherOnce() {
        PointsRedeemed redeemed = new PointsRedeemed(1101, "red-1", "FIXED_20K", "Voucher 20k", 300, 0, "FIXED",
                new BigDecimal("20000"), null, new BigDecimal("100000"), 30);
        EventEnvelope env = EventEnvelope.create(mapper, EventTypes.POINTS_REDEEMED, "loyalty-service", "1101", redeemed);
        service.handleLoyaltyEvent(env);
        service.handleLoyaltyEvent(EventEnvelope.create(mapper, EventTypes.POINTS_REDEEMED, "loyalty-service", "1101",
                redeemed)); // sự kiện trùng redemptionId

        var mine = service.myVouchers(customer(1101));
        assertThat(mine).singleElement().satisfies(v -> {
            assertThat(v.code()).startsWith("PT");
            assertThat(v.status()).isEqualTo("AVAILABLE");
            assertThat(v.source()).isEqualTo("LOYALTY");
        });
        String code = mine.get(0).code();
        // Người khác không dùng được mã cá nhân
        assertThat(order(1102, mouse, "200000", 1, code, "30000").get("reason").asText()).contains("không tồn tại");
        assertThat(order(1101, mouse, "200000", 1, code, "30000").get("voucherDiscount").decimalValue())
                .isEqualByComparingTo("20000");
        assertThat(service.myVouchers(customer(1101)).get(0).status()).isEqualTo("USED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = ?", Integer.class,
                EventTypes.VOUCHER_ISSUED)).isEqualTo(1);
    }

    @Test
    void adminValidation_andPermissions() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> admin.createFlashSale(PM, new FlashSaleRequest("Sai giá", null, now,
                now.plusSeconds(3600), List.of(new FlashSaleItemRequest(mouse, new BigDecimal("250000"), 5, 1)))))
                .isInstanceOf(ApiException.class).hasMessageContaining("phải thấp hơn");
        assertThatThrownBy(() -> admin.createFlashSale(ANALYST, new FlashSaleRequest("x", null, now,
                now.plusSeconds(3600), List.of(new FlashSaleItemRequest(mouse, new BigDecimal("100000"), 5, 1)))))
                .isInstanceOf(ApiException.class);
        flashSale(mouse, "100000", 5, 1);
        // Trùng khung giờ với chiến dịch khác cho cùng sản phẩm
        assertThatThrownBy(() -> flashSale(mouse, "90000", 5, 1)).isInstanceOf(ApiException.class)
                .hasMessageContaining("đã có flash sale");
        String code = voucher("PERCENT", "10", null, null, 1);
        assertThatThrownBy(() -> voucher("PERCENT", "150", null, null, 1)).isInstanceOf(ApiException.class);
        assertThat(admin.listVouchers(ANALYST, code.substring(0, 4), null, 0, 10).content()).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE topic = ?", Integer.class,
                Topics.AUDIT_EVENTS)).isGreaterThanOrEqualTo(2);
        assertThat(admin.stats(ANALYST, 30).activeFlashSales()).isGreaterThanOrEqualTo(1);
    }
}
