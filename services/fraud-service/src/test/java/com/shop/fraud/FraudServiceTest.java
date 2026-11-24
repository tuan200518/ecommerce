package com.shop.fraud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.payload.OrderEvents.OrderCreated;
import com.shop.common.event.payload.OrderEvents.OrderLine;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.event.payload.UserEvents.UserRegistered;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.fraud.domain.RiskCase;
import com.shop.fraud.domain.RiskCaseRepository;
import com.shop.fraud.service.FraudService;
import com.shop.fraud.web.FraudDtos.RiskCaseDto;
import com.shop.support.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:fraud;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.listener.auto-startup=false",
        "shop.outbox.relay-enabled=false",
        "shop.kafka.create-topics=false"
})
class FraudServiceTest {

    private static final AuthenticatedUser ANALYST = new AuthenticatedUser(4, "analyst@shop.local", Set.of(Roles.ANALYST));
    private static final AuthenticatedUser CUSTOMER = new AuthenticatedUser(9, "c@shop.local", Set.of(Roles.CUSTOMER));

    @Autowired
    FraudService service;
    @Autowired
    RiskCaseRepository cases;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    private EventEnvelope event(String type, String key, Object payload) {
        return EventEnvelope.create(mapper, type, "test", key, payload);
    }

    private EventEnvelope orderCreated(long orderId, long userId, String price, int qty, String address) {
        return event(EventTypes.ORDER_CREATED, String.valueOf(orderId), new OrderCreated(orderId, userId,
                List.of(new OrderLine(1, "Laptop", qty, new BigDecimal(price))),
                new BigDecimal(price).multiply(BigDecimal.valueOf(qty)), "MOCK_CARD", Instant.now(), address,
                BigDecimal.ZERO));
    }

    private int outbox(String type, long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = ? AND message_key = ?",
                Integer.class, type, String.valueOf(orderId));
    }

    @Test
    void normalOrder_isAutoApproved_andAssessedExactlyOnce() {
        EventEnvelope created = orderCreated(1001, 501, "990000", 1, "1 Lê Lợi, Q1");
        service.handleOrderEvent(created);
        service.handleOrderEvent(created); // gửi lại cùng sự kiện

        RiskCase c = cases.findById(1001L).orElseThrow();
        assertThat(c.getStatus()).isEqualTo(RiskCase.Status.AUTO_APPROVED);
        assertThat(c.getLevel()).isEqualTo("LOW");
        assertThat(outbox(EventTypes.RISK_ASSESSED, 1001)).isEqualTo(1);
        assertThat(outbox(EventTypes.ORDER_FLAGGED, 1001)).isZero();
    }

    @Test
    void suspiciousOrder_isFlagged_thenReviewerRejects() {
        service.handleUserEvent(event(EventTypes.USER_REGISTERED, "502",
                new UserRegistered(502, "new@shop.local", "Mới", Set.of(Roles.CUSTOMER))));
        service.handleOrderEvent(orderCreated(1002, 502, "28490000", 12, "9 Hai Bà Trưng")); // 341 triệu, 12 cái

        RiskCase c = cases.findById(1002L).orElseThrow();
        assertThat(c.getStatus()).isEqualTo(RiskCase.Status.PENDING_REVIEW);
        assertThat(c.getLevel()).isEqualTo("HIGH");
        assertThat(outbox(EventTypes.ORDER_FLAGGED, 1002)).isEqualTo(1);

        assertThatThrownBy(() -> service.review(CUSTOMER, 1002, true, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.review(ANALYST, 1002, false, " ")).isInstanceOf(ApiException.class);

        RiskCaseDto reviewed = service.review(ANALYST, 1002, false, "Không liên lạc được khách");
        assertThat(reviewed.status()).isEqualTo("REJECTED");
        assertThat(reviewed.signals()).isNotEmpty();
        assertThat(outbox(EventTypes.RISK_REVIEWED, 1002)).isEqualTo(1);
        assertThatThrownBy(() -> service.review(ANALYST, 1002, true, null)).isInstanceOf(ApiException.class);
    }

    @Test
    void sharedAddressAndVelocity_areDetectedFromHistory() {
        String address = "77 Nguyễn Huệ, Quận 1";
        service.handleOrderEvent(orderCreated(2001, 601, "990000", 1, address));
        service.handleOrderEvent(orderCreated(2002, 602, "990000", 1, "77 nguyen hue - quan 1")); // cùng địa chỉ sau chuẩn hóa
        for (long id = 2003; id <= 2006; id++) {
            service.handleOrderEvent(orderCreated(id, 603, "990000", 1, "Khác " + id));
        }
        service.handleOrderEvent(orderCreated(2007, 603, "990000", 1, address));

        RiskCaseDto c = service.get(ANALYST, 2007);
        assertThat(c.signals()).extracting(s -> s.rule()).contains("VELOCITY", "SHARED_ADDRESS");
        assertThat(c.level()).isEqualTo("MEDIUM"); // 55 điểm: ghi nhận nhưng không chặn đơn
    }

    @Test
    void cancelledOrder_closesPendingCase() {
        service.handleUserEvent(event(EventTypes.USER_REGISTERED, "701",
                new UserRegistered(701, "u701@shop.local", "U", Set.of(Roles.CUSTOMER))));
        service.handleOrderEvent(orderCreated(3001, 701, "28490000", 12, "x"));
        assertThat(cases.findById(3001L).orElseThrow().getStatus()).isEqualTo(RiskCase.Status.PENDING_REVIEW);
        service.handleOrderEvent(event(EventTypes.ORDER_CANCELLED, "3001", new OrderStatusChanged(3001, 701,
                "UNDER_REVIEW", "CANCELLED", BigDecimal.ONE, "Khách hủy", List.of())));
        assertThat(cases.findById(3001L).orElseThrow().getStatus()).isEqualTo(RiskCase.Status.CLOSED);
    }

    @Test
    void replayedOldOrder_isRecordedButNotAssessed() {
        EventEnvelope fresh = orderCreated(4001, 801, "990000", 1, "y");
        EventEnvelope old = new EventEnvelope(fresh.eventId(), fresh.eventType(), 1, Instant.now().minusSeconds(3600),
                "test", "4001", fresh.payload());
        service.handleOrderEvent(old);
        assertThat(cases.findById(4001L)).isEmpty();
        assertThat(outbox(EventTypes.RISK_ASSESSED, 4001)).isZero();
    }

    @Test
    void summary_listsRulesAndCounts() {
        service.handleOrderEvent(orderCreated(5001, 901, "990000", 1, "z"));
        assertThat(service.summary(ANALYST).rules()).hasSize(8);
        assertThat(service.summary(ANALYST).byStatus()).containsKey("AUTO_APPROVED");
    }
}
