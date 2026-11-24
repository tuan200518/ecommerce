package com.shop.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.auth.domain.UserAccountRepository;
import com.shop.auth.service.AuthService;
import com.shop.common.event.Topics;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.support.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Khóa tài khoản sau nhiều lần sai mật khẩu + nhật ký kiểm toán cho đăng nhập và quản trị tài khoản. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.listener.auto-startup=false",
        "shop.outbox.relay-enabled=false",
        "shop.kafka.create-topics=false",
        "shop.seed.enabled=false",
        "shop.auth.max-failed-logins=3",
        "shop.auth.lock-duration=PT5M"
})
class LoginSecurityTest {

    @Autowired
    AuthService auth;
    @Autowired
    UserAccountRepository users;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbox_events");
    }

    private List<JsonNode> audits() {
        return jdbc.queryForList("SELECT payload FROM outbox_events WHERE topic = ? ORDER BY id",
                String.class, Topics.AUDIT_EVENTS).stream().map(json -> {
            try {
                return mapper.readTree(json).get("payload");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    private static HttpStatus statusOf(Runnable call) {
        try {
            call.run();
            return HttpStatus.OK;
        } catch (ApiException e) {
            return e.getStatus();
        }
    }

    @Test
    void repeatedWrongPasswords_lockAccount_evenForCorrectPassword_andAreAudited() {
        auth.createUser("lock@shop.local", "Secret@123", "Lock Test", Set.of(Roles.CUSTOMER));

        assertThat(statusOf(() -> auth.login("lock@shop.local", "wrong-1"))).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statusOf(() -> auth.login("LOCK@shop.local", "wrong-2"))).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Lần sai thứ 3 = max-failed-logins → khóa ngay
        assertThat(statusOf(() -> auth.login("lock@shop.local", "wrong-3"))).isEqualTo(HttpStatus.LOCKED);
        // Đúng mật khẩu cũng bị chặn trong thời gian khóa
        assertThatThrownBy(() -> auth.login("lock@shop.local", "Secret@123"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("tạm khóa");

        assertThat(users.findByEmailIgnoreCase("lock@shop.local").orElseThrow().getLockedUntil()).isNotNull();
        assertThat(audits()).extracting(a -> a.get("action").asText())
                .containsExactly("LOGIN_FAILED", "LOGIN_FAILED", "ACCOUNT_LOCKED", "LOGIN_BLOCKED");
        assertThat(audits()).allSatisfy(a -> {
            assertThat(a.get("outcome").asText()).isEqualTo("FAILURE");
            assertThat(a.get("actorEmail").asText()).isEqualTo("lock@shop.local");
        });
    }

    @Test
    void successfulLogin_resetsFailureCounter() {
        auth.createUser("reset@shop.local", "Secret@123", "Reset Test", Set.of(Roles.CUSTOMER));
        assertThat(statusOf(() -> auth.login("reset@shop.local", "wrong"))).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statusOf(() -> auth.login("reset@shop.local", "wrong"))).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(auth.login("reset@shop.local", "Secret@123").accessToken()).isNotBlank();
        // Bộ đếm về 0 → lại được sai 2 lần mà không bị khóa
        assertThat(statusOf(() -> auth.login("reset@shop.local", "wrong"))).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statusOf(() -> auth.login("reset@shop.local", "wrong"))).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(auth.login("reset@shop.local", "Secret@123").accessToken()).isNotBlank();
        // Đăng nhập thành công sau khi sai được ghi lại để dễ điều tra
        assertThat(audits()).extracting(a -> a.get("action").asText()).contains("LOGIN_SUCCEEDED");
    }

    @Test
    void unknownEmail_returnsSameErrorAsWrongPassword() {
        assertThatThrownBy(() -> auth.login("nobody@shop.local", "x"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Email hoặc mật khẩu không đúng");
        assertThat(audits()).singleElement().satisfies(a -> {
            assertThat(a.get("action").asText()).isEqualTo("LOGIN_FAILED");
            assertThat(a.get("actorId").isNull()).isTrue();
        });
    }

    @Test
    void adminChangingRoles_isAudited() {
        long target = auth.createUser("staff@shop.local", "Secret@123", "Staff", Set.of(Roles.CUSTOMER)).getId();
        AuthenticatedUser admin = new AuthenticatedUser(999_999, "admin@shop.local", Set.of(Roles.ADMIN));

        auth.changeRoles(admin, target, Set.of(Roles.CUSTOMER, Roles.ANALYST));
        auth.setEnabled(admin, target, false);

        assertThat(audits()).extracting(a -> a.get("action").asText())
                .containsExactly("USER_ROLES_CHANGED", "USER_DISABLED");
        JsonNode roles = audits().get(0);
        assertThat(roles.get("actorEmail").asText()).isEqualTo("admin@shop.local");
        assertThat(roles.get("resourceId").asText()).isEqualTo(String.valueOf(target));
        assertThat(roles.get("detail").asText()).contains("[CUSTOMER] → [ANALYST, CUSTOMER]");
        // Tài khoản bị khóa không đăng nhập được
        assertThat(statusOf(() -> auth.login("staff@shop.local", "Secret@123"))).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
