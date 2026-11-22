package com.shop.gateway;

import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.Set;

/**
 * Chính sách truy cập ở tầng Gateway (lớp bảo vệ thứ nhất).
 * Các service vẫn tự kiểm tra quyền chi tiết (lớp thứ hai).
 */
public final class AccessPolicy {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private record Rule(HttpMethod method, String pattern) {
        boolean matches(HttpMethod m, String path) {
            return (method == null || method.equals(m)) && MATCHER.match(pattern, path);
        }
    }

    private record RoleRule(String pattern, Set<String> roles) {
    }

    /** Không cần đăng nhập. */
    private static final List<Rule> PUBLIC = List.of(
            new Rule(HttpMethod.POST, "/api/auth/login"),
            new Rule(HttpMethod.POST, "/api/auth/register"),
            new Rule(HttpMethod.POST, "/api/auth/logout"),
            new Rule(HttpMethod.GET, "/api/products"),
            new Rule(HttpMethod.GET, "/api/products/**"),
            new Rule(HttpMethod.GET, "/api/categories"),
            new Rule(HttpMethod.GET, "/api/categories/**"),
            new Rule(HttpMethod.GET, "/api/inventory/availability"),
            new Rule(null, "/actuator/health"));

    private static final Set<String> STAFF = Set.of(Roles.ADMIN, Roles.ANALYST, Roles.PRODUCT_MANAGER,
            Roles.WAREHOUSE_MANAGER);

    /** Đường dẫn yêu cầu vai trò cụ thể. */
    private static final List<RoleRule> ROLE_RULES = List.of(
            new RoleRule("/api/auth/users/**", Set.of(Roles.ADMIN)),
            new RoleRule("/api/simulator", Set.of(Roles.ADMIN)),
            new RoleRule("/api/simulator/**", Set.of(Roles.ADMIN)),
            new RoleRule("/api/analytics/**", STAFF),
            new RoleRule("/api/payments", Set.of(Roles.ADMIN, Roles.ANALYST)),
            new RoleRule("/api/payments/summary", Set.of(Roles.ADMIN, Roles.ANALYST)));

    private AccessPolicy() {
    }

    public static boolean isPublic(HttpMethod method, String path) {
        return method == HttpMethod.OPTIONS || PUBLIC.stream().anyMatch(r -> r.matches(method, path));
    }

    public static boolean isAllowed(AuthenticatedUser user, String path) {
        for (RoleRule rule : ROLE_RULES) {
            if (MATCHER.match(rule.pattern(), path)) {
                return rule.roles().stream().anyMatch(user::hasRole);
            }
        }
        return true;
    }
}
