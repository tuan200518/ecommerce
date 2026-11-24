package com.shop.promotion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Promotion Service: chiến dịch flash sale (giá ưu đãi, giới hạn suất và giới hạn mỗi tài khoản), mã giảm giá
 * (công khai hoặc cá nhân – đổi từ điểm Loyalty) và định giá khuyến mãi cho mọi đơn hàng trong Saga.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PromotionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PromotionServiceApplication.class, args);
    }
}
