package com.shop.auth.service;

import com.shop.auth.domain.UserAccount;
import com.shop.auth.domain.UserAccountRepository;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.UserEvents.UserLoggedIn;
import com.shop.common.event.payload.UserEvents.UserRegistered;
import com.shop.common.event.payload.UserEvents.UserRolesChanged;
import com.shop.common.security.JwtService;
import com.shop.common.security.Roles;
import com.shop.support.outbox.OutboxService;
import com.shop.support.web.ApiException;
import com.shop.support.web.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

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

    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final OutboxService outbox;

    public AuthService(UserAccountRepository users, PasswordEncoder encoder, JwtService jwt, OutboxService outbox) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.outbox = outbox;
    }

    @Transactional
    public TokenResponse register(String email, String password, String fullName) {
        UserAccount user = createUser(email, password, fullName, Set.of(Roles.CUSTOMER));
        return token(user);
    }

    /** Dùng chung cho đăng ký và tạo tài khoản mẫu; phát UserRegistered (User Service tạo hồ sơ). */
    @Transactional
    public UserAccount createUser(String email, String password, String fullName, Set<String> roles) {
        String normalized = email.trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(normalized)) {
            throw ApiException.conflict("Email đã được sử dụng");
        }
        UserAccount user = users.save(new UserAccount(normalized, encoder.encode(password), fullName.trim(), roles));
        outbox.publish(Topics.USER_EVENTS, EventTypes.USER_REGISTERED, String.valueOf(user.getId()),
                new UserRegistered(user.getId(), user.getEmail(), user.getFullName(), Set.copyOf(user.getRoles())));
        return user;
    }

    @Transactional
    public TokenResponse login(String email, String password) {
        UserAccount user = users.findByEmailIgnoreCase(email.trim())
                .filter(u -> encoder.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("Email hoặc mật khẩu không đúng"));
        if (!user.isEnabled()) {
            throw ApiException.forbidden("Tài khoản đã bị khóa");
        }
        user.recordLogin();
        outbox.publish(Topics.USER_EVENTS, EventTypes.USER_LOGGED_IN, String.valueOf(user.getId()),
                new UserLoggedIn(user.getId(), user.getEmail()));
        return token(user);
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
    public UserDto changeRoles(long actorId, long userId, Set<String> roles) {
        if (roles.isEmpty() || !Roles.ALL.containsAll(roles)) {
            throw ApiException.badRequest("Danh sách vai trò không hợp lệ: " + roles);
        }
        UserAccount user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Không tìm thấy tài khoản"));
        if (actorId == userId && !roles.contains(Roles.ADMIN)) {
            throw ApiException.badRequest("Không thể tự gỡ quyền ADMIN của chính mình");
        }
        user.setRoles(roles);
        outbox.publish(Topics.USER_EVENTS, EventTypes.USER_ROLES_CHANGED, String.valueOf(user.getId()),
                new UserRolesChanged(user.getId(), user.getEmail(), Set.copyOf(roles)));
        return UserDto.from(user);
    }

    @Transactional
    public UserDto setEnabled(long actorId, long userId, boolean enabled) {
        if (actorId == userId && !enabled) {
            throw ApiException.badRequest("Không thể tự khóa tài khoản của chính mình");
        }
        UserAccount user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Không tìm thấy tài khoản"));
        user.setEnabled(enabled);
        return UserDto.from(user);
    }

    private TokenResponse token(UserAccount user) {
        String token = jwt.issue(user.getId(), user.getEmail(), user.getRoles());
        return new TokenResponse(token, "Bearer", jwt.ttl().toSeconds(), UserDto.from(user));
    }
}
