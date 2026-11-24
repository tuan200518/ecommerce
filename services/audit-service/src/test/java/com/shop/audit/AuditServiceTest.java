package com.shop.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.audit.domain.AuditEntryRepository;
import com.shop.audit.service.AuditService;
import com.shop.audit.service.AuditService.Filter;
import com.shop.audit.web.AuditDtos.AuditDto;
import com.shop.audit.web.AuditDtos.SummaryDto;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.AuditEvents.AuditRecorded;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.support.web.ApiException;
import com.shop.support.web.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:audit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.listener.auto-startup=false",
        "shop.outbox.relay-enabled=false",
        "shop.kafka.create-topics=false",
        "shop.audit.suspicious-ip-emails=3"
})
class AuditServiceTest {

    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1, "admin@shop.local", Set.of(Roles.ADMIN));
    private static final AuthenticatedUser ANALYST = new AuthenticatedUser(4, "analyst@shop.local", Set.of(Roles.ANALYST));

    @Autowired
    AuditService service;
    @Autowired
    AuditEntryRepository entries;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        entries.deleteAll();
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM processed_events");
    }

    private EventEnvelope audit(String source, AuditRecorded payload) {
        return EventEnvelope.create(mapper, EventTypes.AUDIT_RECORDED, source,
                payload.resourceType() + ":" + payload.resourceId(), payload);
    }

    private static AuditRecorded failedLogin(String email, String ip) {
        return new AuditRecorded(null, email, null, "LOGIN_FAILED", "USER", null, "FAILURE", "Sai mật khẩu", ip);
    }

    private int adminNotifications(String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE topic = ? AND payload LIKE ?",
                Integer.class, Topics.NOTIFICATION_EVENTS, "%" + type + "%");
    }

    @Test
    void storesEntries_idempotently_andSearchesByFilters() {
        EventEnvelope update = audit("product-service", new AuditRecorded(1L, "admin@shop.local", "ADMIN",
                "PRODUCT_UPDATED", "PRODUCT", "42", "SUCCESS", "Giá 100.000 → 90.000", "203.0.113.5"));
        service.handleAuditEvent(update);
        service.handleAuditEvent(update); // Kafka gửi lại → không ghi trùng
        service.handleAuditEvent(audit("order-service", new AuditRecorded(2L, "wh@shop.local", "WAREHOUSE_MANAGER",
                "ORDER_CANCELLED_BY_STAFF", "ORDER", "7", "SUCCESS", "Khách yêu cầu", "10.0.0.2")));

        assertThat(entries.count()).isEqualTo(2);
        PageResponse<AuditDto> byResource = service.search(ADMIN,
                new Filter(null, null, "product", "42", null, null, null, null, null), 0, 20);
        assertThat(byResource.content()).singleElement().satisfies(e -> {
            assertThat(e.source()).isEqualTo("product-service");
            assertThat(e.detail()).contains("90.000");
            assertThat(e.correlationId()).isNotBlank();
        });
        assertThat(service.search(ADMIN, new Filter("khách", null, null, null, null, null, null, null, null), 0, 20)
                .content()).extracting(AuditDto::action).containsExactly("ORDER_CANCELLED_BY_STAFF");
        assertThat(service.search(ADMIN, new Filter(null, null, null, null, null, "WH@", null, null, null), 0, 20)
                .content()).hasSize(1);
        // Ký tự đại diện LIKE được thoát → "%" không khớp mọi dòng
        assertThat(service.search(ADMIN, new Filter("%", null, null, null, null, null, null, null, null), 0, 20)
                .content()).isEmpty();
    }

    @Test
    void onlyAdminCanRead() {
        assertThatThrownBy(() -> service.search(ANALYST,
                new Filter(null, null, null, null, null, null, null, null, null), 0, 20))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.summary(ANALYST, 24)).isInstanceOf(ApiException.class);
    }

    @Test
    void accountLocked_notifiesAdmins() {
        service.handleAuditEvent(audit("auth-service", new AuditRecorded(9L, "victim@shop.local", null,
                "ACCOUNT_LOCKED", "USER", "9", "FAILURE", "Sai mật khẩu 5 lần", "198.51.100.1")));
        assertThat(adminNotifications("SECURITY_ACCOUNT_LOCKED")).isEqualTo(1);
    }

    @Test
    void failuresAcrossManyAccountsFromOneIp_raiseSingleCredentialStuffingAlert() {
        for (String email : new String[]{"a@x.vn", "b@x.vn", "b@x.vn", "c@x.vn", "d@x.vn"}) {
            service.handleAuditEvent(audit("auth-service", failedLogin(email, "198.51.100.66")));
        }
        // Ngưỡng 3 tài khoản: cảnh báo đúng 1 lần khi vượt ngưỡng, không cảnh báo lại ở tài khoản thứ 4
        assertThat(adminNotifications("SECURITY_CREDENTIAL_STUFFING")).isEqualTo(1);
        // IP khác chưa đủ ngưỡng
        service.handleAuditEvent(audit("auth-service", failedLogin("a@x.vn", "198.51.100.77")));
        assertThat(adminNotifications("SECURITY_CREDENTIAL_STUFFING")).isEqualTo(1);

        SummaryDto summary = service.summary(ADMIN, 24);
        assertThat(summary.failedLogins()).isEqualTo(6);
        assertThat(summary.suspiciousIps().get(0).ip()).isEqualTo("198.51.100.66");
        assertThat(summary.suspiciousIps().get(0).accounts()).isEqualTo(4);
        assertThat(service.actions(ADMIN)).containsExactly("LOGIN_FAILED");
    }
}
