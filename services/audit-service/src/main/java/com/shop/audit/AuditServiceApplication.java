package com.shop.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Audit Service: lưu nhật ký kiểm toán từ topic audit-events (mọi service ghi qua {@code AuditTrail}),
 * cho phép quản trị viên tra cứu và phát cảnh báo bảo mật (tài khoản bị khóa, dò mật khẩu hàng loạt từ một IP).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
