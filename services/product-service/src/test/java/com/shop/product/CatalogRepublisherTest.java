package com.shop.product;

import com.shop.common.event.EventTypes;
import com.shop.product.service.CatalogRepublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Danh mục được phát lại lên product-events để service mới dựng được read-model dù sự kiện cũ đã hết hạn. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:product-republish;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.listener.auto-startup=false",
        "shop.outbox.relay-enabled=false",
        "shop.kafka.create-topics=false",
        "shop.cache.enabled=false"
})
class CatalogRepublisherTest {

    @Autowired
    CatalogRepublisher republisher;
    @Autowired
    JdbcTemplate jdbc;

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void everyProductIsRepublishedWithItsCurrentState() {
        int products = count("SELECT COUNT(*) FROM products");
        assertThat(products).isPositive();
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("UPDATE products SET active = FALSE WHERE id = (SELECT MIN(id) FROM products)");

        assertThat(republisher.republish()).isEqualTo(products);

        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE topic = 'product-events'")).isEqualTo(products);
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE event_type = '" + EventTypes.PRODUCT_DISCONTINUED + "'"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE event_type = '" + EventTypes.PRODUCT_UPDATED + "'"))
                .isEqualTo(products - 1);
    }
}
