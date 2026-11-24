package com.shop.loyalty.domain;

import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Hibernate 6 tạo CHECK constraint cho cột enum (point_transactions.type) và KHÔNG cập nhật nó khi enum có giá trị
 * mới (REDEMPTION) với ddl-auto=update → bỏ ràng buộc cũ trên CSDL đã có dữ liệu. Giá trị enum vẫn được kiểm soát
 * ở tầng ứng dụng. Phụ thuộc EntityManagerFactory để chạy SAU khi Hibernate đã cập nhật schema.
 */
@Component
public class LoyaltySchemaPatches implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(LoyaltySchemaPatches.class);

    private final JdbcTemplate jdbc;

    public LoyaltySchemaPatches(JdbcTemplate jdbc, EntityManagerFactory emf) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            jdbc.execute("ALTER TABLE point_transactions DROP CONSTRAINT IF EXISTS point_transactions_type_check");
        } catch (Exception e) {
            log.debug("Không bỏ được ràng buộc point_transactions_type_check: {}", e.getMessage());
        }
    }
}
