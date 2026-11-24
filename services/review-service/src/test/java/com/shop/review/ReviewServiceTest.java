package com.shop.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.payload.OrderEvents.OrderLine;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.review.service.ReviewService;
import com.shop.review.web.ReviewDtos.QuestionDto;
import com.shop.review.web.ReviewDtos.ReviewDto;
import com.shop.review.web.ReviewDtos.ReviewRequest;
import com.shop.support.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:review;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.listener.auto-startup=false",
        "shop.outbox.relay-enabled=false",
        "shop.kafka.create-topics=false"
})
class ReviewServiceTest {

    private static final AuthenticatedUser PM = new AuthenticatedUser(2, "pm@shop.local", Set.of(Roles.PRODUCT_MANAGER));

    @Autowired
    ReviewService service;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    private static AuthenticatedUser customer(long id) {
        return new AuthenticatedUser(id, "customer" + id + "@shop.local", Set.of(Roles.CUSTOMER));
    }

    private void order(String type, long orderId, long userId, String status, long productId) {
        service.handleOrderEvent(EventEnvelope.create(mapper, type, "test", String.valueOf(orderId),
                new OrderStatusChanged(orderId, userId, "X", status, BigDecimal.TEN, null,
                        List.of(new OrderLine(productId, "Tai nghe #" + productId, 1, BigDecimal.TEN)), "addr")));
    }

    private int outbox(String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = ?", Integer.class, type);
    }

    @Test
    void onlyBuyersWhoReceivedTheProduct_canReview_once() {
        AuthenticatedUser buyer = customer(11);
        assertThat(service.eligibility(Optional.empty(), 100).canReview()).isFalse();
        assertThat(service.eligibility(Optional.of(buyer), 100).canReview()).isFalse();

        order(EventTypes.ORDER_CONFIRMED, 1, 11, "CONFIRMED", 100);
        assertThat(service.eligibility(Optional.of(buyer), 100).reason()).contains("sau khi nhận được hàng");
        assertThatThrownBy(() -> service.submit(buyer, 100, new ReviewRequest(5, "Tốt", List.of())))
                .isInstanceOf(ApiException.class);

        order(EventTypes.ORDER_STATUS_CHANGED, 1, 11, "DELIVERED", 100);
        assertThat(service.eligibility(Optional.of(buyer), 100).canReview()).isTrue();

        ReviewDto review = service.submit(buyer, 100, new ReviewRequest(4, "  Âm thanh hay  ",
                List.of("https://img.example/1.jpg")));
        assertThat(review.comment()).isEqualTo("Âm thanh hay");
        assertThat(review.authorName()).isEqualTo("cus***@shop.local");
        assertThat(review.productName()).isEqualTo("Tai nghe #100");
        assertThat(outbox(EventTypes.REVIEW_SUBMITTED)).isGreaterThanOrEqualTo(1);

        assertThat(service.eligibility(Optional.of(buyer), 100).alreadyReviewed()).isTrue();
        assertThatThrownBy(() -> service.submit(buyer, 100, new ReviewRequest(1, "lần 2", List.of())))
                .isInstanceOf(ApiException.class);
        // Người khác chưa mua thì không được đánh giá
        assertThatThrownBy(() -> service.submit(customer(12), 100, new ReviewRequest(1, null, List.of())))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void cancelledOrder_removesPendingPurchase() {
        order(EventTypes.ORDER_CONFIRMED, 2, 21, "CONFIRMED", 200);
        order(EventTypes.ORDER_CANCELLED, 2, 21, "CANCELLED", 200);
        assertThat(service.eligibility(Optional.of(customer(21)), 200).reason()).contains("Chỉ khách đã mua");
    }

    @Test
    void summary_and_moderation() {
        for (long u = 31; u <= 33; u++) {
            order(EventTypes.ORDER_CONFIRMED, u, u, "CONFIRMED", 300);
            order(EventTypes.ORDER_STATUS_CHANGED, u, u, "DELIVERED", 300);
        }
        service.submit(customer(31), 300, new ReviewRequest(5, null, null));
        service.submit(customer(32), 300, new ReviewRequest(4, null, null));
        ReviewDto spam = service.submit(customer(33), 300, new ReviewRequest(1, "quảng cáo", null));

        assertThat(service.summary(300).average()).isEqualTo(3.3);
        assertThatThrownBy(() -> service.setVisibility(customer(31), spam.id(), false, null))
                .isInstanceOf(ApiException.class);
        service.setVisibility(PM, spam.id(), false, "Spam");

        assertThat(service.summary(300).count()).isEqualTo(2);
        assertThat(service.summary(300).average()).isEqualTo(4.5);
        assertThat(service.summary(300).distribution()).containsEntry(5, 1L).containsEntry(1, 0L);
        assertThat(service.productReviews(300, null, 0, 10).content()).hasSize(2);
        assertThat(outbox(EventTypes.REVIEW_VISIBILITY_CHANGED)).isEqualTo(1);
    }

    @Test
    void questionsAndAnswers() {
        QuestionDto q = service.ask(customer(41), 400, "Có bảo hành không?");
        assertThat(service.questionQueue(PM, false, 0, 10).content()).extracting(QuestionDto::id).contains(q.id());

        // Khách chưa mua không được trả lời
        assertThatThrownBy(() -> service.answer(customer(42), q.id(), "Không biết")).isInstanceOf(ApiException.class);

        QuestionDto answered = service.answer(PM, q.id(), "Bảo hành 12 tháng");
        assertThat(answered.answered()).isTrue();
        assertThat(answered.answers()).singleElement().satisfies(a -> assertThat(a.staff()).isTrue());
        assertThat(service.productQuestions(400, 0, 10)).hasSize(1);
        assertThat(outbox(EventTypes.NOTIFICATION_REQUESTED)).isGreaterThanOrEqualTo(1);
    }
}
