package com.shop.order.domain;

import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Hibernate 6 tạo ràng buộc CHECK cho cột enum (vd. {@code status in ('PENDING',...)}) khi tạo bảng,
 * nhưng {@code ddl-auto=update} không cập nhật ràng buộc đó khi enum có thêm giá trị (UNDER_REVIEW).
 * Trên database tạo từ phiên bản trước, bỏ các ràng buộc cũ để lưu được trạng thái mới;
 * tính hợp lệ của trạng thái đã do {@link OrderStatus} kiểm soát.
 * <p>
 * Phụ thuộc EntityManagerFactory để chạy SAU khi Hibernate cập nhật schema.
 */
@Component
public class OrderSchemaPatches implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(OrderSchemaPatches.class);

    private final JdbcTemplate jdbc;

    public OrderSchemaPatches(JdbcTemplate jdbc, EntityManagerFactory emf) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        drop("orders", "orders_status_check");
        drop("order_status_history", "order_status_history_from_status_check");
        drop("order_status_history", "order_status_history_to_status_check");
    }

    private void drop(String table, String constraint) {
        try {
            jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraint);
        } catch (Exception e) {
            log.debug("Không bỏ được ràng buộc {}.{}: {}", table, constraint, e.getMessage());
        }
    }
}
