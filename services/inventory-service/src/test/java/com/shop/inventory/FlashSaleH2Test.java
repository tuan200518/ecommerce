package com.shop.inventory;

import org.springframework.boot.test.context.SpringBootTest;

/** Chạy kịch bản Flash Sale trên H2 (không cần Docker / Kafka). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:invdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.hikari.maximum-pool-size=20",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.listener.auto-startup=false",
        "shop.outbox.relay-enabled=false",
        "shop.kafka.create-topics=false"
})
class FlashSaleH2Test extends FlashSaleScenario {
}
