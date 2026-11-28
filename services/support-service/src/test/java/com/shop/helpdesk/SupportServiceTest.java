package com.shop.helpdesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.OrderEvents.OrderCreated;
import com.shop.common.event.payload.OrderEvents.OrderLine;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.helpdesk.service.SupportService;
import com.shop.helpdesk.service.SupportService.QueueFilter;
import com.shop.helpdesk.web.SupportDtos.CreateTicketRequest;
import com.shop.helpdesk.web.SupportDtos.RateRequest;
import com.shop.helpdesk.web.SupportDtos.StaffUpdateRequest;
import com.shop.helpdesk.web.SupportDtos.TicketDetailDto;
import com.shop.helpdesk.web.SupportDtos.TicketDto;
import com.shop.support.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
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
        "spring.datasource.url=jdbc:h2:mem:support;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.listener.auto-startup=false",
        "shop.outbox.relay-enabled=false",
        "shop.kafka.create-topics=false",
        "shop.support.jobs-enabled=false",
        // SLA ưu tiên cao = 0 → quá hạn ngay (kiểm tra cảnh báo SLA); ưu tiên thường vẫn 2 giờ
        "shop.support.sla-high=PT0S",
        "shop.support.auto-close-after=PT0S",
        "shop.support.max-open-per-customer=3"
})
class SupportServiceTest {

    private static final AuthenticatedUser AGENT = new AuthenticatedUser(900, "cskh@shop.local", Set.of(Roles.SUPPORT_AGENT));
    private static final AuthenticatedUser ANALYST = new AuthenticatedUser(901, "analyst@shop.local", Set.of(Roles.ANALYST));
    private static final AtomicLong IDS = new AtomicLong(100);

    @Autowired
    SupportService service;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbox_events");
    }

    private static AuthenticatedUser customer() {
        long id = IDS.incrementAndGet();
        return new AuthenticatedUser(id, "c" + id + "@shop.local", Set.of(Roles.CUSTOMER));
    }

    private long order(AuthenticatedUser owner, String total) {
        long orderId = IDS.incrementAndGet();
        service.handleOrderEvent(EventEnvelope.create(mapper, EventTypes.ORDER_CREATED, "order-service",
                String.valueOf(orderId), new OrderCreated(orderId, owner.userId(),
                        List.of(new OrderLine(1, "SP", 1, new BigDecimal(total))), new BigDecimal(total), "MOCK_CARD",
                        Instant.now())));
        return orderId;
    }

    private void orderStatus(long orderId, long userId, String status, Instant at) {
        OrderStatusChanged payload = new OrderStatusChanged(orderId, userId, "CONFIRMED", status, new BigDecimal("100000"),
                "Khách yêu cầu", List.of(), "addr");
        String type = "CANCELLED".equals(status) ? EventTypes.ORDER_CANCELLED : EventTypes.ORDER_STATUS_CHANGED;
        service.handleOrderEvent(new EventEnvelope(UUID.randomUUID().toString(), type, 1, at, "order-service",
                String.valueOf(orderId), mapper.valueToTree(payload), null));
    }

    private TicketDetailDto ticket(AuthenticatedUser c, String category, Long orderId) {
        return service.create(c, new CreateTicketRequest("Đơn chưa giao", category, orderId, "Đơn của tôi giao chậm"));
    }

    private int outbox(String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = ?", Integer.class, type);
    }

    private static HttpStatus statusOf(Runnable r) {
        try {
            r.run();
            return HttpStatus.OK;
        } catch (ApiException e) {
            return e.getStatus();
        }
    }

    @Test
    void customerCreatesTicketForOwnOrder_only() {
        AuthenticatedUser c = customer();
        AuthenticatedUser other = customer();
        long orderId = order(c, "350000");

        TicketDetailDto t = ticket(c, "SHIPPING", orderId);
        assertThat(t.ticket().code()).isEqualTo("HT-" + t.ticket().id());
        assertThat(t.ticket().status()).isEqualTo("OPEN");
        assertThat(t.ticket().priority()).isEqualTo("NORMAL");
        assertThat(t.order().status()).isEqualTo("PENDING");
        assertThat(t.messages()).singleElement().satisfies(m -> assertThat(m.kind()).isEqualTo("CUSTOMER"));
        assertThat(outbox(EventTypes.TICKET_CREATED)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE topic = ? AND payload LIKE ?",
                Integer.class, Topics.NOTIFICATION_EVENTS, "%SUPPORT_AGENT%")).isEqualTo(1);

        assertThatThrownBy(() -> ticket(other, "ORDER", orderId)).isInstanceOf(ApiException.class)
                .hasMessageContaining("Không tìm thấy đơn hàng");
        assertThat(statusOf(() -> ticket(AGENT, "ORDER", null))).isEqualTo(HttpStatus.FORBIDDEN);
        // Người khác không xem được (404, không lộ sự tồn tại)
        assertThat(statusOf(() -> service.detail(other, t.ticket().id()))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(service.myOrders(c)).extracting(o -> o.orderId()).containsExactly(orderId);
    }

    @Test
    void conversationFlow_assignsAgent_tracksUnread_reopens_andRatesOnce() {
        AuthenticatedUser c = customer();
        long id = ticket(c, "ORDER", null).ticket().id();

        service.postMessage(AGENT, id, "Chào bạn, mình kiểm tra ngay");
        // Danh sách của khách hiện 1 tin chưa đọc; mở chi tiết → đã đọc
        assertThat(service.mine(c, 0, 10).content()).singleElement().satisfies(t -> assertThat(t.unread()).isEqualTo(1));
        TicketDetailDto afterReply = service.detail(c, id);
        assertThat(afterReply.ticket().status()).isEqualTo("IN_PROGRESS");
        assertThat(afterReply.ticket().assigneeEmail()).isEqualTo("cskh@shop.local");
        assertThat(afterReply.ticket().firstResponseAt()).isNotNull();
        assertThat(service.mine(c, 0, 10).content().get(0).unread()).isZero();
        assertThat(afterReply.messages()).extracting(m -> m.authorName()).contains("CSKH · cskh");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE payload LIKE ?", Integer.class,
                "%SUPPORT_REPLY%")).isEqualTo(1);

        service.update(AGENT, id, new StaffUpdateRequest("WAITING_CUSTOMER", null, null, null));
        service.postMessage(c, id, "Mã vận đơn là gì ạ?");
        assertThat(service.detail(AGENT, id).ticket().status()).isEqualTo("IN_PROGRESS");

        assertThat(statusOf(() -> service.rate(c, id, new RateRequest(5, null)))).isEqualTo(HttpStatus.CONFLICT);
        service.update(AGENT, id, new StaffUpdateRequest("RESOLVED", null, null, "Đã gửi mã vận đơn"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE payload LIKE ?", Integer.class,
                "%SUPPORT_RESOLVED%")).isEqualTo(1);

        // Khách trả lời sau khi đã giải quyết → mở lại
        service.postMessage(c, id, "Vẫn chưa nhận được hàng");
        TicketDetailDto reopened = service.detail(AGENT, id);
        assertThat(reopened.ticket().status()).isEqualTo("IN_PROGRESS");
        assertThat(reopened.messages()).anyMatch(m -> m.kind().equals("SYSTEM") && m.content().contains("mở lại"));

        service.close(c, id);
        assertThat(statusOf(() -> service.postMessage(c, id, "xin chào"))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(service.rate(c, id, new RateRequest(4, "Nhanh")).rating()).isEqualTo(4);
        assertThat(statusOf(() -> service.rate(c, id, new RateRequest(5, null)))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(service.summary(AGENT).avgRating()).isNotNull();
        // Thao tác của nhân viên có audit
        assertThat(outbox(EventTypes.AUDIT_RECORDED)).isEqualTo(2);
    }

    @Test
    void permissions_analystReadsButCannotReply() {
        AuthenticatedUser c = customer();
        long id = ticket(c, "OTHER", null).ticket().id();
        assertThat(service.detail(ANALYST, id).ticket().id()).isEqualTo(id);
        assertThat(statusOf(() -> service.postMessage(ANALYST, id, "x"))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf(() -> service.update(ANALYST, id, new StaffUpdateRequest("RESOLVED", null, null, null))))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf(() -> service.queue(c, new QueueFilter(null, null, null, null, null), 0, 10)))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void highPriority_slaBreach_isNotifiedOnce_andQueueSortsByPriority() {
        AuthenticatedUser c = customer();
        long normal = ticket(c, "PRODUCT", null).ticket().id();
        TicketDetailDto payment = ticket(c, "PAYMENT", null); // PAYMENT → HIGH, SLA 0 trong test
        assertThat(payment.ticket().priority()).isEqualTo("HIGH");
        assertThat(payment.ticket().overdue()).isTrue();

        assertThat(service.notifySlaBreaches()).isGreaterThanOrEqualTo(1);
        assertThat(service.notifySlaBreaches()).isZero(); // chỉ cảnh báo một lần
        assertThat(service.summary(AGENT).overdue()).isGreaterThanOrEqualTo(1);

        List<TicketDto> overdue = service.queue(AGENT, new QueueFilter(null, null, null, true, null), 0, 50).content();
        assertThat(overdue).extracting(TicketDto::id).contains(payment.ticket().id()).doesNotContain(normal);

        service.update(AGENT, normal, new StaffUpdateRequest(null, "URGENT", "ME", null));
        List<TicketDto> mine = service.queue(AGENT, new QueueFilter("ACTIVE", null, "ME", null, null), 0, 50).content();
        assertThat(mine).extracting(TicketDto::id).containsExactly(normal);
        List<TicketDto> all = service.queue(AGENT, new QueueFilter("ACTIVE", null, null, null, null), 0, 100).content();
        assertThat(all.get(0).priority()).isEqualTo("URGENT");
        // Tìm theo mã HT-<id>
        assertThat(service.queue(AGENT, new QueueFilter(null, null, null, null, "HT-" + normal), 0, 10).content())
                .extracting(TicketDto::id).contains(normal);
    }

    @Test
    void orderStatusChange_postsSystemMessage_onlyForRecentEvents() {
        AuthenticatedUser c = customer();
        long orderId = order(c, "200000");
        long id = ticket(c, "ORDER", orderId).ticket().id();

        orderStatus(orderId, c.userId(), "SHIPPING", Instant.now().minus(Duration.ofHours(2))); // replay cũ
        assertThat(service.detail(c, id).messages()).noneMatch(m -> m.kind().equals("SYSTEM"));
        assertThat(service.detail(c, id).order().status()).isEqualTo("SHIPPING"); // read-model vẫn cập nhật

        orderStatus(orderId, c.userId(), "CANCELLED", Instant.now());
        assertThat(service.detail(c, id).messages()).anyMatch(m -> m.kind().equals("SYSTEM")
                && m.content().contains("Đã hủy") && m.content().contains("Khách yêu cầu"));
    }

    @Test
    void limitsOpenTickets_andAutoClosesResolved() {
        AuthenticatedUser c = customer();
        long first = ticket(c, "OTHER", null).ticket().id();
        ticket(c, "OTHER", null);
        ticket(c, "OTHER", null);
        assertThat(statusOf(() -> ticket(c, "OTHER", null))).isEqualTo(HttpStatus.CONFLICT);

        service.update(AGENT, first, new StaffUpdateRequest("RESOLVED", null, null, null));
        assertThat(service.autoClose()).isGreaterThanOrEqualTo(1);
        assertThat(service.detail(c, first).ticket().status()).isEqualTo("CLOSED");
        // Đã có chỗ cho yêu cầu mới
        assertThat(ticket(c, "OTHER", null).ticket().status()).isEqualTo("OPEN");
    }
}
