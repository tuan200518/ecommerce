package com.shop.review.service;

import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.NotificationEvents.NotificationRequested;
import com.shop.common.event.payload.OrderEvents.OrderLine;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.event.payload.ReviewEvents.ReviewChanged;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.review.domain.Answer;
import com.shop.review.domain.AnswerRepository;
import com.shop.review.domain.Purchase;
import com.shop.review.domain.PurchaseRepository;
import com.shop.review.domain.Question;
import com.shop.review.domain.QuestionRepository;
import com.shop.review.domain.Review;
import com.shop.review.domain.ReviewRepository;
import com.shop.review.web.ReviewDtos.AnswerDto;
import com.shop.review.web.ReviewDtos.EligibilityDto;
import com.shop.review.web.ReviewDtos.QuestionDto;
import com.shop.review.web.ReviewDtos.ReviewDto;
import com.shop.review.web.ReviewDtos.ReviewRequest;
import com.shop.review.web.ReviewDtos.SummaryDto;
import com.shop.support.kafka.EventReader;
import com.shop.support.audit.AuditTrail;
import com.shop.support.outbox.IdempotencyGuard;
import com.shop.support.outbox.OutboxService;
import com.shop.support.web.Access;
import com.shop.support.web.ApiException;
import com.shop.support.web.PageResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Review Service: đánh giá sản phẩm (chỉ người mua đã nhận hàng), hỏi đáp và kiểm duyệt.
 * Quyền đánh giá dựa trên read-model {@link Purchase} xây từ order-events, không gọi đồng bộ sang Order Service.
 */
@Service
public class ReviewService {

    static final String CONSUMER = "review-service";
    private static final String[] MODERATORS = {Roles.ADMIN, Roles.PRODUCT_MANAGER};

    private final PurchaseRepository purchases;
    private final ReviewRepository reviews;
    private final QuestionRepository questions;
    private final AnswerRepository answers;
    private final OutboxService outbox;
    private final IdempotencyGuard guard;
    private final EventReader reader;
    private final AuditTrail audit;

    public ReviewService(PurchaseRepository purchases, ReviewRepository reviews, QuestionRepository questions,
                         AnswerRepository answers, OutboxService outbox, IdempotencyGuard guard, EventReader reader,
                         AuditTrail audit) {
        this.audit = audit;
        this.purchases = purchases;
        this.reviews = reviews;
        this.questions = questions;
        this.answers = answers;
        this.outbox = outbox;
        this.guard = guard;
        this.reader = reader;
    }

    // ------------------------------------------------------------------ Kafka (order-events)

    @Transactional
    public void handleOrderEvent(EventEnvelope env) {
        switch (env.eventType()) {
            case EventTypes.ORDER_CONFIRMED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    OrderStatusChanged p = reader.payload(env, OrderStatusChanged.class);
                    if (!purchases.existsByOrderId(p.orderId()) && p.items() != null) {
                        for (OrderLine line : p.items()) {
                            purchases.save(new Purchase(p.orderId(), p.userId(), line.productId(), line.productName()));
                        }
                    }
                }
            }
            case EventTypes.ORDER_STATUS_CHANGED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    OrderStatusChanged p = reader.payload(env, OrderStatusChanged.class);
                    if ("DELIVERED".equals(p.status())) {
                        purchases.findByOrderId(p.orderId()).forEach(Purchase::markDelivered);
                    }
                }
            }
            case EventTypes.ORDER_CANCELLED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    purchases.deleteUndelivered(reader.payload(env, OrderStatusChanged.class).orderId());
                }
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ Đánh giá

    @Transactional(readOnly = true)
    public EligibilityDto eligibility(Optional<AuthenticatedUser> user, long productId) {
        if (user.isEmpty()) {
            return new EligibilityDto(false, false, "Đăng nhập để đánh giá sản phẩm");
        }
        long userId = user.get().userId();
        if (reviews.existsByUserIdAndProductId(userId, productId)) {
            return new EligibilityDto(false, true, "Bạn đã đánh giá sản phẩm này");
        }
        if (!purchases.findByUserIdAndProductIdAndDeliveredTrue(userId, productId).isEmpty()) {
            return new EligibilityDto(true, false, null);
        }
        if (purchases.existsByUserIdAndProductId(userId, productId)) {
            return new EligibilityDto(false, false, "Bạn có thể đánh giá sau khi nhận được hàng");
        }
        return new EligibilityDto(false, false, "Chỉ khách đã mua và nhận sản phẩm mới được đánh giá");
    }

    @Transactional
    public ReviewDto submit(AuthenticatedUser user, long productId, ReviewRequest req) {
        List<Purchase> delivered = purchases.findByUserIdAndProductIdAndDeliveredTrue(user.userId(), productId);
        if (delivered.isEmpty()) {
            throw ApiException.forbidden("Chỉ khách đã mua và nhận sản phẩm mới được đánh giá");
        }
        if (reviews.existsByUserIdAndProductId(user.userId(), productId)) {
            throw ApiException.conflict("Bạn đã đánh giá sản phẩm này");
        }
        Purchase purchase = delivered.get(0);
        List<String> images = req.images() == null ? List.of()
                : req.images().stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        Review review;
        try {
            review = reviews.saveAndFlush(new Review(productId, purchase.getProductName(), user.userId(),
                    displayName(user), purchase.getOrderId(), req.rating(), trimToNull(req.comment()), images));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("Bạn đã đánh giá sản phẩm này");
        }
        outbox.publish(Topics.REVIEW_EVENTS, EventTypes.REVIEW_SUBMITTED, String.valueOf(productId),
                new ReviewChanged(review.getId(), productId, user.userId(), review.getRating(), true, !images.isEmpty()));
        return ReviewDto.from(review);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReviewDto> productReviews(long productId, Integer rating, int page, int size) {
        PageRequest pageable = pageable(page, size);
        Page<Review> result = rating == null
                ? reviews.findByProductIdAndVisibleTrueOrderByCreatedAtDesc(productId, pageable)
                : reviews.findByProductIdAndVisibleTrueAndRatingOrderByCreatedAtDesc(productId, rating, pageable);
        return PageResponse.of(result, ReviewDto::from);
    }

    @Transactional(readOnly = true)
    public SummaryDto summary(long productId) {
        Map<Integer, Long> distribution = new LinkedHashMap<>();
        for (int star = 5; star >= 1; star--) {
            distribution.put(star, 0L);
        }
        long count = 0;
        long total = 0;
        for (Object[] row : reviews.ratingDistribution(productId)) {
            int star = ((Number) row[0]).intValue();
            long c = ((Number) row[1]).longValue();
            distribution.put(star, c);
            count += c;
            total += star * c;
        }
        double average = count == 0 ? 0 : Math.round(total * 10.0 / count) / 10.0;
        return new SummaryDto(productId, average, count, distribution);
    }

    // ------------------------------------------------------------------ Kiểm duyệt

    @Transactional(readOnly = true)
    public PageResponse<ReviewDto> moderationList(AuthenticatedUser user, Boolean visible, int page, int size) {
        Access.requireAnyRole(user, MODERATORS);
        Page<Review> result = visible == null ? reviews.findAllByOrderByCreatedAtDesc(pageable(page, size))
                : reviews.findByVisibleOrderByCreatedAtDesc(visible, pageable(page, size));
        return PageResponse.of(result, ReviewDto::from);
    }

    @Transactional
    public ReviewDto setVisibility(AuthenticatedUser user, long reviewId, boolean visible, String note) {
        Access.requireAnyRole(user, MODERATORS);
        Review review = reviews.findById(reviewId).orElseThrow(() -> ApiException.notFound("Không tìm thấy đánh giá"));
        if (review.isVisible() != visible) {
            review.setVisible(visible, trimToNull(note));
            outbox.publish(Topics.REVIEW_EVENTS, EventTypes.REVIEW_VISIBILITY_CHANGED,
                    String.valueOf(review.getProductId()),
                    new ReviewChanged(review.getId(), review.getProductId(), review.getUserId(), review.getRating(),
                            visible, !review.imageList().isEmpty()));
            audit.record(user, visible ? "REVIEW_SHOWN" : "REVIEW_HIDDEN", "REVIEW", review.getId(),
                    "Sản phẩm #" + review.getProductId() + (trimToNull(note) == null ? "" : ": " + note.trim()));
        }
        return ReviewDto.from(review);
    }

    // ------------------------------------------------------------------ Hỏi đáp

    @Transactional(readOnly = true)
    public List<QuestionDto> productQuestions(long productId, int page, int size) {
        return withAnswers(questions.findByProductIdOrderByCreatedAtDesc(productId, pageable(page, size)).getContent());
    }

    @Transactional
    public QuestionDto ask(AuthenticatedUser user, long productId, String content) {
        Question q = questions.save(new Question(productId, user.userId(), displayName(user), content.trim()));
        return QuestionDto.from(q, List.of());
    }

    /** Nhân viên (ADMIN / PRODUCT_MANAGER) hoặc khách đã nhận sản phẩm được trả lời. */
    @Transactional
    public QuestionDto answer(AuthenticatedUser user, long questionId, String content) {
        Question q = questions.findById(questionId).orElseThrow(() -> ApiException.notFound("Không tìm thấy câu hỏi"));
        boolean staff = user.hasAnyRole(MODERATORS);
        if (!staff && purchases.findByUserIdAndProductIdAndDeliveredTrue(user.userId(), q.getProductId()).isEmpty()) {
            throw ApiException.forbidden("Chỉ nhân viên hoặc khách đã mua sản phẩm mới được trả lời");
        }
        answers.save(new Answer(q.getId(), user.userId(), staff ? "Nhân viên cửa hàng" : displayName(user), staff,
                content.trim()));
        if (staff) {
            q.markAnswered();
        }
        if (!q.getUserId().equals(user.userId())) {
            outbox.publish(Topics.NOTIFICATION_EVENTS, EventTypes.NOTIFICATION_REQUESTED, "user-" + q.getUserId(),
                    new NotificationRequested(q.getUserId(), null, "QUESTION_ANSWERED",
                            "Câu hỏi của bạn đã có trả lời", abbreviate(content.trim(), 160),
                            "product:" + q.getProductId()));
        }
        return withAnswers(List.of(q)).get(0);
    }

    @Transactional(readOnly = true)
    public PageResponse<QuestionDto> questionQueue(AuthenticatedUser user, boolean answered, int page, int size) {
        Access.requireAnyRole(user, MODERATORS);
        Page<Question> result = questions.findByAnsweredOrderByCreatedAtAsc(answered, pageable(page, size));
        Map<Long, List<AnswerDto>> byQuestion = answersFor(result.getContent());
        return PageResponse.of(result, q -> QuestionDto.from(q, byQuestion.getOrDefault(q.getId(), List.of())));
    }

    // ------------------------------------------------------------------ Hỗ trợ

    private List<QuestionDto> withAnswers(List<Question> list) {
        Map<Long, List<AnswerDto>> byQuestion = answersFor(list);
        return list.stream().map(q -> QuestionDto.from(q, byQuestion.getOrDefault(q.getId(), List.of()))).toList();
    }

    private Map<Long, List<AnswerDto>> answersFor(List<Question> list) {
        if (list.isEmpty()) {
            return Map.of();
        }
        return answers.findByQuestionIdInOrderByIdAsc(list.stream().map(Question::getId).toList()).stream()
                .collect(Collectors.groupingBy(Answer::getQuestionId, LinkedHashMap::new,
                        Collectors.mapping(AnswerDto::from, Collectors.toList())));
    }

    /** Ẩn bớt email khi hiển thị công khai: "customer12@shop.local" → "cus***@shop.local". */
    static String displayName(AuthenticatedUser user) {
        String email = user.email();
        if (email == null || !email.contains("@")) {
            return "Khách hàng #" + user.userId();
        }
        String local = email.substring(0, email.indexOf('@'));
        String visible = local.length() <= 3 ? local.substring(0, 1) : local.substring(0, 3);
        return visible + "***" + email.substring(email.indexOf('@'));
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static PageRequest pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50));
    }
}
