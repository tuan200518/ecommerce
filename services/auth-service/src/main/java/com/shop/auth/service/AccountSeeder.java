package com.shop.auth.service;

import com.shop.auth.domain.UserAccountRepository;
import com.shop.common.security.Roles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Tài khoản mẫu cho demo và kiểm thử tải (chỉ tạo khi database trống). */
@Component
@ConditionalOnProperty(prefix = "shop.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AccountSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountSeeder.class);

    private final UserAccountRepository users;
    private final AuthService auth;
    private final int demoCustomers;

    public AccountSeeder(UserAccountRepository users, AuthService auth,
                         @Value("${shop.seed.demo-customers:50}") int demoCustomers) {
        this.users = users;
        this.auth = auth;
        this.demoCustomers = demoCustomers;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Tài khoản CSKH (phiên bản 2.2) – tạo cả trên CSDL đã có dữ liệu từ phiên bản trước
        if (!users.existsByEmailIgnoreCase("support@shop.local")) {
            auth.createUser("support@shop.local", "Support@123", "Nhân viên CSKH", Set.of(Roles.SUPPORT_AGENT));
        }
        if (users.count() > 1) {
            return;
        }
        auth.createUser("admin@shop.local", "Admin@123", "Quản trị viên", Set.of(Roles.ADMIN));
        auth.createUser("pm@shop.local", "Manager@123", "Quản lý sản phẩm", Set.of(Roles.PRODUCT_MANAGER));
        auth.createUser("warehouse@shop.local", "Manager@123", "Quản lý kho", Set.of(Roles.WAREHOUSE_MANAGER));
        auth.createUser("analyst@shop.local", "Analyst@123", "Chuyên viên phân tích", Set.of(Roles.ANALYST));
        auth.createUser("customer@shop.local", "Customer@123", "Nguyễn Văn Khách", Set.of(Roles.CUSTOMER));
        for (int i = 1; i <= demoCustomers; i++) {
            auth.createUser("customer" + i + "@shop.local", "Customer@123", "Khách hàng " + i, Set.of(Roles.CUSTOMER));
        }
        log.info("Đã tạo tài khoản mẫu: admin@shop.local / Admin@123 và {} khách hàng demo", demoCustomers + 1);
    }
}
