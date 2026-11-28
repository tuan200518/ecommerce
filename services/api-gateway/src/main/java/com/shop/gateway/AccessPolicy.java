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
            new Rule(HttpMethod.GET, "/api/reviews/products/**"),
            new Rule(HttpMethod.GET, "/api/loyalty/referrals/*"),
            new Rule(HttpMethod.GET, "/api/loyalty/tiers"),
            new Rule(HttpMethod.GET, "/api/shipments/carriers"),
            new Rule(HttpMethod.GET, "/api/loyalty/rewards"),
            new Rule(HttpMethod.GET, "/api/promotions/flash-sales/active"),
            new Rule(HttpMethod.GET, "/api/promotions/vouchers/public"),
            // Gợi ý sản phẩm: công khai (khách đã đăng nhập vẫn được gắn X-User-* → gợi ý cá nhân hóa).
            // Liệt kê từng đường dẫn để /api/recommendations/admin/** KHÔNG bị coi là công khai.
            new Rule(HttpMethod.GET, "/api/recommendations/products/*/similar"),
            new Rule(HttpMethod.GET, "/api/recommendations/products/*/bought-together"),
            new Rule(HttpMethod.GET, "/api/recommendations/for-you"),
            new Rule(HttpMethod.GET, "/api/recommendations/trending"),
            new Rule(HttpMethod.GET, "/api/recommendations/cart"),
            new Rule(HttpMethod.POST, "/api/recommendations/clicks"));

    private static final Set<String> STAFF = Set.of(Roles.ADMIN, Roles.ANALYST, Roles.PRODUCT_MANAGER,
            Roles.WAREHOUSE_MANAGER, Roles.SUPPORT_AGENT);

    /** Đường dẫn yêu cầu vai trò cụ thể – quy tắc ĐẦU TIÊN khớp được áp dụng. */
    private static final List<RoleRule> ROLE_RULES = List.of(
            new RoleRule("/api/auth/users/**", Set.of(Roles.ADMIN)),
            new RoleRule("/api/simulator", Set.of(Roles.ADMIN)),
            new RoleRule("/api/simulator/**", Set.of(Roles.ADMIN)),
            new RoleRule("/api/analytics/**", STAFF),
            new RoleRule("/api/payments", Set.of(Roles.ADMIN, Roles.ANALYST)),
            new RoleRule("/api/payments/summary", Set.of(Roles.ADMIN, Roles.ANALYST)),
            new RoleRule("/api/fraud/**", Set.of(Roles.ADMIN, Roles.ANALYST)),
            new RoleRule("/api/loyalty/admin/**", Set.of(Roles.ADMIN, Roles.ANALYST)),
            new RoleRule("/api/reviews/admin/**", Set.of(Roles.ADMIN, Roles.PRODUCT_MANAGER)),
            // Promotion: quản trị chiến dịch/voucher (service kiểm tra lại quyền ghi: ADMIN/PRODUCT_MANAGER)
            new RoleRule("/api/promotions/admin/**", Set.of(Roles.ADMIN, Roles.PRODUCT_MANAGER, Roles.ANALYST)),
            // Hỗ trợ khách hàng: hàng đợi CSKH (service chỉ cho ADMIN/SUPPORT_AGENT trả lời, ANALYST chỉ xem)
            new RoleRule("/api/support/admin/**", Set.of(Roles.ADMIN, Roles.SUPPORT_AGENT, Roles.ANALYST)),
            // Lịch sử cảnh báo hệ thống + kênh chuyển tiếp
            new RoleRule("/api/notifications/admin/**", Set.of(Roles.ADMIN)),
            // Hiệu quả gợi ý + hồ sơ sở thích khách hàng
            new RoleRule("/api/recommendations/admin/**", Set.of(Roles.ADMIN, Roles.ANALYST, Roles.PRODUCT_MANAGER)),
            // Nhật ký kiểm toán: chỉ quản trị viên
            new RoleRule("/api/audit", Set.of(Roles.ADMIN)),
            new RoleRule("/api/audit/**", Set.of(Roles.ADMIN)),
            // Khách xem vận đơn của đơn mình (service kiểm tra chủ sở hữu); các API vận đơn khác chỉ cho nhân viên
            new RoleRule("/api/shipments/orders/**", Roles.ALL),
            new RoleRule("/api/shipments", Set.of(Roles.ADMIN, Roles.WAREHOUSE_MANAGER, Roles.ANALYST)),
            new RoleRule("/api/shipments/**", Set.of(Roles.ADMIN, Roles.WAREHOUSE_MANAGER, Roles.ANALYST)));

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
