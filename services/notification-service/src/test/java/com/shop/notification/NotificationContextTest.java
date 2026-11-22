package com.shop.notification;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Smoke test: toàn bộ cấu hình Spring khởi tạo được (H2 thay PostgreSQL, không cần Kafka broker). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:notification;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.listener.auto-startup=false",
        "shop.outbox.relay-enabled=false",
        "shop.kafka.create-topics=false"
})
class NotificationContextTest {

    @Test
    void contextLoads() {
    }
}
