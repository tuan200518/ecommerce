package com.shop.loyalty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.event.payload.ReviewEvents.ReviewChanged;
import com.shop.common.event.payload.UserEvents.UserRegistered;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.loyalty.domain.LoyaltyAccountRepository;
import com.shop.loyalty.service.LoyaltyService;
import com.shop.loyalty.web.LoyaltyDtos.AccountDto;
import com.shop.support.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:loyalty;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.listener.auto-startup=false",
        "shop.outbox.relay-enabled=false",
        "shop.kafka.create-topics=false"
})
class LoyaltyServiceTest {

    @Autowired
    LoyaltyService service;
    @Autowired
    LoyaltyAccountRepository accounts;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    private static AuthenticatedUser user(long id) {
        return new AuthenticatedUser(id, "u" + id + "@shop.local", Set.of(Roles.CUSTOMER));
    }

    private EventEnvelope order(String type, long orderId, long userId, String total) {
        return EventEnvelope.create(mapper, type, "test", String.valueOf(orderId),
                new OrderStatusChanged(orderId, userId, "AWAITING_PAYMENT",
                        type.equals(EventTypes.ORDER_CANCELLED) ? "CANCELLED" : "CONFIRMED",
                        new BigDecimal(total), "lý do", List.of(), "addr"));
    }

    private void register(long userId, String referralCode, String... roles) {
        service.handleUserEvent(EventEnvelope.create(mapper, EventTypes.USER_REGISTERED, "test", String.valueOf(userId),
                new UserRegistered(userId, "u" + userId + "@shop.local", "U" + userId,
                        roles.length == 0 ? Set.of(Roles.CUSTOMER) : Set.of(roles), referralCode)));
    }

    private void review(String type, long reviewId, long userId, boolean visible) {
        service.handleReviewEvent(EventEnvelope.create(mapper, type, "test", "1",
                new ReviewChanged(reviewId, 1, userId, 5, visible, false)));
    }

    @Test
    void confirmedOrder_earnsPointsPlusFirstOrderBonus_exactlyOnce() {
        EventEnvelope confirmed = order(EventTypes.ORDER_CONFIRMED, 1, 101, "2000000");
        service.handleOrderEvent(confirmed);
        service.handleOrderEvent(confirmed); // gửi lại cùng sự kiện
        service.handleOrderEvent(order(EventTypes.ORDER_CONFIRMED, 1, 101, "2000000")); // sự kiện khác, cùng đơn

        AccountDto me = service.me(user(101));
        assertThat(me.balance()).isEqualTo(200 + 200);
        assertThat(me.firstOrderCompleted()).isTrue();
        assertThat(me.tier().code()).isEqualTo("BRONZE");
        assertThat(service.myTransactions(user(101), 0, 10).content()).hasSize(2);
    }

    @Test
    void tierUpgrade_increasesMultiplierForNextOrders() {
        service.handleOrderEvent(order(EventTypes.ORDER_CONFIRMED, 2, 102, "6000000"));
        AccountDto afterFirst = service.me(user(102));
        assertThat(afterFirst.tier().code()).isEqualTo("SILVER");
        assertThat(afterFirst.balance()).isEqualTo(600 + 200);
        assertThat(afterFirst.nextTier().code()).isEqualTo("GOLD");
        assertThat(afterFirst.amountToNextTier()).isEqualByComparingTo("14000000");

        service.handleOrderEvent(order(EventTypes.ORDER_CONFIRMED, 3, 102, "1000000"));
        assertThat(service.me(user(102)).balance()).isEqualTo(800 + 120); // 100 điểm × 1.2
    }

    @Test
    void cancelledOrder_reversesPointsAndAllowsFirstOrderBonusAgain() {
        service.handleOrderEvent(order(EventTypes.ORDER_CONFIRMED, 4, 103, "1000000"));
        assertThat(service.me(user(103)).balance()).isEqualTo(300);

        EventEnvelope cancelled = order(EventTypes.ORDER_CANCELLED, 4, 103, "1000000");
        service.handleOrderEvent(cancelled);
        service.handleOrderEvent(cancelled);
        AccountDto me = service.me(user(103));
        assertThat(me.balance()).isZero();
        assertThat(me.spend12Months()).isEqualByComparingTo("0");
        assertThat(me.firstOrderCompleted()).isFalse();

        service.handleOrderEvent(order(EventTypes.ORDER_CONFIRMED, 5, 103, "1000000"));
        assertThat(service.me(user(103)).balance()).isEqualTo(300);
    }

    @Test
    void referral_rewardsReferrerOnFirstValidOrder_only() {
        register(201, null);
        String code = service.me(user(201)).referralCode();
        assertThat(service.checkReferral(code.toLowerCase()).valid()).isTrue();
        assertThat(service.checkReferral("KHONGCO").valid()).isFalse();

        register(202, code);          // được giới thiệu hợp lệ
        register(203, "KHONGCO");     // mã sai → bỏ qua
        service.handleOrderEvent(order(EventTypes.ORDER_CONFIRMED, 20, 202, "500000"));
        service.handleOrderEvent(order(EventTypes.ORDER_CONFIRMED, 21, 202, "500000")); // đơn thứ 2 không thưởng thêm
        service.handleOrderEvent(order(EventTypes.ORDER_CONFIRMED, 22, 203, "500000"));

        assertThat(service.me(user(201)).balance()).isEqualTo(500);
        assertThat(service.me(user(201)).referralCount()).isEqualTo(1);
        assertThat(service.me(user(202)).referred()).isTrue();

        // Đơn đầu bị hủy → thu hồi thưởng giới thiệu
        service.handleOrderEvent(order(EventTypes.ORDER_CANCELLED, 20, 202, "500000"));
        assertThat(service.me(user(201)).balance()).isZero();
    }

    @Test
    void staffAccounts_doNotJoinProgram() {
        register(301, null, Roles.WAREHOUSE_MANAGER);
        assertThat(accounts.findById(301L)).isEmpty();
    }

    @Test
    void reviewBonus_isLimitedPerDay_andReversedWhenHidden() {
        for (long r = 1; r <= 6; r++) {
            review(EventTypes.REVIEW_SUBMITTED, 400 + r, 401, true);
        }
        assertThat(service.me(user(401)).balance()).isEqualTo(5 * 50);

        review(EventTypes.REVIEW_VISIBILITY_CHANGED, 401, 401, false);
        assertThat(service.me(user(401)).balance()).isEqualTo(4 * 50);
    }

    @Test
    void redeem_spendsPoints_publishesPointsRedeemed_andRejectsInsufficientBalance() {
        service.handleOrderEvent(order(EventTypes.ORDER_CONFIRMED, 50, 501, "3000000")); // 300 + 200 thưởng = 500
        assertThat(service.rewards()).extracting(r -> r.id()).contains("FREESHIP", "FIXED_20K");

        var result = service.redeem(user(501), "FIXED_20K"); // 300 điểm
        assertThat(result.balance()).isEqualTo(200);
        assertThat(service.me(user(501)).balance()).isEqualTo(200);
        assertThat(service.me(user(501)).lifetimeEarned()).isEqualTo(500); // đổi điểm không làm giảm tổng tích lũy
        assertThat(service.myTransactions(user(501), 0, 10).content().get(0).type()).isEqualTo("REDEMPTION");

        String payload = jdbc.queryForObject("SELECT payload FROM outbox_events WHERE event_type = ? ORDER BY id DESC LIMIT 1",
                String.class, EventTypes.POINTS_REDEEMED);
        assertThat(payload).contains("\"rewardId\":\"FIXED_20K\"").contains("\"voucherType\":\"FIXED\"")
                .contains(result.redemptionId());

        // Không đủ điểm cho phần thưởng 800 điểm
        assertThatThrownBy(() -> service.redeem(user(501), "PERCENT_10")).isInstanceOf(ApiException.class)
                .hasMessageContaining("Không đủ điểm");
        assertThatThrownBy(() -> service.redeem(user(501), "KHONG_CO")).isInstanceOf(ApiException.class);
        // Nhân viên không đổi điểm
        assertThatThrownBy(() -> service.redeem(new AuthenticatedUser(9, "a@shop.local", Set.of(Roles.ADMIN)),
                "FREESHIP")).isInstanceOf(ApiException.class);

        // Hủy đơn sau khi đã đổi: điểm tích của đơn bị trừ lại, giao dịch đổi thưởng giữ nguyên (số dư có thể âm)
        service.handleOrderEvent(order(EventTypes.ORDER_CANCELLED, 50, 501, "3000000"));
        assertThat(service.me(user(501)).balance()).isEqualTo(200 - 500);
    }
}
