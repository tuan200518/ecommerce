package com.shop.auth.service;

import com.shop.auth.domain.UserAccount;
import com.shop.auth.domain.UserAccountRepository;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.UserEvents.UserLoggedIn;
import com.shop.common.event.payload.UserEvents.UserRegistered;
import com.shop.common.event.payload.UserEvents.UserRolesChanged;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.JwtService;
import com.shop.common.security.Roles;
import com.shop.support.audit.AuditTrail;
import com.shop.support.outbox.OutboxService;
import com.shop.support.web.ApiException;
import com.shop.support.web.PageResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class AuthService {

    public record UserDto(long id, String email, String fullName, Set<String> roles, boolean enabled,
                          Instant createdAt, Instant lastLoginAt) {
        public static UserDto from(UserAccount u) {
            return new UserDto(u.getId(), u.getEmail(), u.getFullName(), Set.copyOf(u.getRoles()), u.isEnabled(),
                    u.getCreatedAt(), u.getLastLoginAt());
        }
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn, UserDto user) {
    }

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String BAD_CREDENTIALS = "Email hoặc mật khẩu không đúng";

    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final OutboxService outbox;
    private final AuditTrail audit;
    private final MeterRegistry meters;
    private final int maxFailedLogins;
    private final Duration lockDuration;
    /** BCrypt của một chuỗi ngẫu nhiên – chỉ để so khớp "giả" khi email không tồn tại. */
    private final String dummyHash;

    public AuthService(UserAccountRepository users, PasswordEncoder encoder, JwtService jwt, OutboxService outbox,
                       AuditTrail audit, MeterRegistry meters,
                       @Value("${shop.auth.max-failed-logins:5}") int maxFailedLogins,
                       @Value("${shop.auth.lock-duration:PT5M}") Duration lockDuration) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.outbox = outbox;
        this.audit = audit;
        this.meters = meters;
        this.maxFailedLogins = maxFailedLogins;
        this.lockDuration = lockDuration;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public TokenResponse register(String email, String password, String fullName, String referralCode) {
        UserAccount user = createUser(email, password, fullName, Set.of(Roles.CUSTOMER), referralCode);
        return token(user);
    }

    /** Dùng chung cho đăng ký và tạo tài khoản mẫu; phát UserRegistered (User Service tạo hồ sơ). */
    @Transactional
    public UserAccount createUser(String email, String password, String fullName, Set<String> roles) {
        return createUser(email, password, fullName, roles, null);
    }

    @Transactional
    public UserAccount createUser(String email, String password, String fullName, Set<String> roles,
                                  String referralCode) {
        String normalized = email.trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(normalized)) {
            throw ApiException.conflict("Email đã được sử dụng");
        }
        UserAccount user = users.save(new UserAccount(normalized, encoder.encode(password), fullName.trim(), roles));
        outbox.publish(Topics.USER_EVENTS, EventTypes.USER_REGISTERED, String.valueOf(user.getId()),
                new UserRegistered(user.getId(), user.getEmail(), user.getFullName(), Set.copyOf(user.getRoles()),
                        referralCode == null || referralCode.isBlank() ? null : referralCode.trim().toUpperCase()));
        return user;
    }

    /**
     * Đăng nhập. Chống dò mật khẩu theo từng tài khoản: sai {@code shop.auth.max-failed-logins} lần liên tiếp →
     * tạm khóa {@code shop.auth.lock-duration} (Gateway giới hạn thêm số lần thử theo IP).
     * Mọi lần thất bại đều được ghi audit log trong transaction riêng.
     * <p>
     * {@code noRollbackFor}: số lần sai vẫn được lưu dù request kết thúc bằng lỗi 401.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse login(String email, String password) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        Instant now = Instant.now();
        Optional<UserAccount> found = users.findByEmailIgnoreCase(normalized);
        if (found.isEmpty()) {
            encoder.matches(password, dummyHash); // thời gian phản hồi như khi email tồn tại → khó dò email
            loginFailed(null, normalized, "LOGIN_FAILED", "Email không tồn tại");
            throw ApiException.unauthorized(BAD_CREDENTIALS);
        }
        UserAccount user = found.get();
        if (user.isLocked(now)) {
            loginFailed(user.getId(), normalized, "LOGIN_BLOCKED", "Đăng nhập khi đang bị tạm khóa");
            throw lockedError(user, now);
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            boolean lockedNow = user.registerFailedLogin(maxFailedLogins, lockDuration, now);
            if (lockedNow) {
                log.warn("Tạm khóa tài khoản {} sau {} lần sai mật khẩu liên tiếp", user.getEmail(), maxFailedLogins);
                loginFailed(user.getId(), normalized, "ACCOUNT_LOCKED",
                        "Sai mật khẩu " + maxFailedLogins + " lần liên tiếp – tạm khóa " + lockDuration.toMinutes() + " phút");
                throw lockedError(user, now);
            }
            loginFailed(user.getId(), normalized, "LOGIN_FAILED",
                    "Sai mật khẩu (lần " + user.getFailedLoginAttempts() + "/" + maxFailedLogins + ")");
            throw ApiException.unauthorized(BAD_CREDENTIALS);
        }
        if (!user.isEnabled()) {
            loginFailed(user.getId(), normalized, "LOGIN_BLOCKED", "Tài khoản đã bị quản trị viên khóa");
            throw ApiException.forbidden("Tài khoản đã bị khóa");
        }
        boolean hadFailures = user.getFailedLoginAttempts() > 0;
        user.recordLogin();
        meters.counter("shop.auth.logins", "result", "success").increment();
        // Ghi audit đăng nhập thành công của nhân viên (và của khách vừa có lần sai) – không ghi mọi lượt của khách
        if (hadFailures || user.getRoles().stream().anyMatch(r -> !Roles.CUSTOMER.equals(r))) {
            audit.record(new AuthenticatedUser(user.getId(), user.getEmail(), Set.copyOf(user.getRoles())),
                    "LOGIN_SUCCEEDED", "USER", user.getId(), hadFailures ? "Đăng nhập thành công sau lần sai mật khẩu" : null);
        }
        outbox.publish(Topics.USER_EVENTS, EventTypes.USER_LOGGED_IN, String.valueOf(user.getId()),
                new UserLoggedIn(user.getId(), user.getEmail()));
        return token(user);
    }

    private void loginFailed(Long userId, String email, String action, String detail) {
        meters.counter("shop.auth.logins", "result", action.toLowerCase(Locale.ROOT)).increment();
        audit.recordFailure(userId, email, action, "USER", userId, detail);
    }

    private static ApiException lockedError(UserAccount user, Instant now) {
        long minutes = Math.max(1, Duration.between(now, user.getLockedUntil()).toSeconds() / 60 + 1);
        return new ApiException(HttpStatus.LOCKED,
                "Tài khoản tạm khóa do đăng nhập sai nhiều lần. Vui lòng thử lại sau khoảng " + minutes + " phút");
    }

    @Transactional(readOnly = true)
    public UserDto me(long userId) {
        return users.findById(userId).map(UserDto::from)
                .orElseThrow(() -> ApiException.notFound("Không tìm thấy tài khoản"));
    }

    // ------------------------------------------------------------------ Quản trị tài khoản

    @Transactional(readOnly = true)
    public PageResponse<UserDto> list(String q, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        if (q == null || q.isBlank()) {
            return PageResponse.of(users.findAllByOrderByIdAsc(pr), UserDto::from);
        }
        return PageResponse.of(users.findByEmailContainingIgnoreCaseOrFullNameContainingIgnoreCaseOrderByIdAsc(
                q.trim(), q.trim(), pr), UserDto::from);
    }

    @Transactional
    public UserDto changeRoles(AuthenticatedUser actor, long userId, Set<String> roles) {
        if (roles.isEmpty() || !Roles.ALL.containsAll(roles)) {
            throw ApiException.badRequest("Danh sách vai trò không hợp lệ: " + roles);
        }
        UserAccount user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Không tìm thấy tài khoản"));
        if (actor.userId() == userId && !roles.contains(Roles.ADMIN)) {
            throw ApiException.badRequest("Không thể tự gỡ quyền ADMIN của chính mình");
        }
        Set<String> before = new TreeSet<>(user.getRoles());
        user.setRoles(roles);
        outbox.publish(Topics.USER_EVENTS, EventTypes.USER_ROLES_CHANGED, String.valueOf(user.getId()),
                new UserRolesChanged(user.getId(), user.getEmail(), Set.copyOf(roles)));
        audit.record(actor, "USER_ROLES_CHANGED", "USER", user.getId(),
                user.getEmail() + ": " + before + " → " + new TreeSet<>(roles));
        return UserDto.from(user);
    }

    @Transactional
    public UserDto setEnabled(AuthenticatedUser actor, long userId, boolean enabled) {
        if (actor.userId() == userId && !enabled) {
            throw ApiException.badRequest("Không thể tự khóa tài khoản của chính mình");
        }
        UserAccount user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Không tìm thấy tài khoản"));
        user.setEnabled(enabled);
        audit.record(actor, enabled ? "USER_ENABLED" : "USER_DISABLED", "USER", user.getId(), user.getEmail());
        return UserDto.from(user);
    }

    private TokenResponse token(UserAccount user) {
        String token = jwt.issue(user.getId(), user.getEmail(), user.getRoles());
        return new TokenResponse(token, "Bearer", jwt.ttl().toSeconds(), UserDto.from(user));
    }
}
