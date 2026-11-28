package com.shop.helpdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Support Service: yêu cầu hỗ trợ (ticket) gắn với đơn hàng, chat realtime (SSE) giữa khách và nhân viên CSKH,
 * cam kết thời gian phản hồi (SLA), tự đóng yêu cầu đã giải quyết và đánh giá mức độ hài lòng.
 * <p>
 * Package {@code com.shop.helpdesk} (không dùng {@code com.shop.support} – đã thuộc thư viện service-support).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SupportServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupportServiceApplication.class, args);
    }
}
