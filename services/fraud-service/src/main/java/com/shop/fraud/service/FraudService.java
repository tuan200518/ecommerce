package com.shop.fraud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.FraudEvents.RiskAssessed;
import com.shop.common.event.payload.FraudEvents.RiskReviewed;
import com.shop.common.event.payload.FraudEvents.RiskSignal;
import com.shop.common.event.payload.NotificationEvents.NotificationRequested;
import com.shop.common.event.payload.OrderEvents.OrderCreated;
import com.shop.common.event.payload.OrderEvents.OrderLine;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.event.payload.PaymentEvents.PaymentResult;
import com.shop.common.event.payload.UserEvents.UserRegistered;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.fraud.FraudProperties;
import com.shop.fraud.domain.CustomerProfile;
import com.shop.fraud.domain.CustomerProfileRepository;
import com.shop.fraud.domain.OrderRecord;
import com.shop.fraud.domain.OrderRecordRepository;
import com.shop.fraud.domain.PaymentFailure;
import com.shop.fraud.domain.PaymentFailureRepository;
import com.shop.fraud.domain.RiskCase;
import com.shop.fraud.domain.RiskCaseRepository;
import com.shop.fraud.web.FraudDtos.RiskCaseDto;
import com.shop.fraud.web.FraudDtos.SummaryDto;
import com.shop.support.kafka.EventReader;
import com.shop.support.audit.AuditTrail;
import com.shop.support.outbox.IdempotencyGuard;
import com.shop.support.outbox.OutboxService;
import com.shop.support.web.Access;
import com.shop.support.web.ApiException;
import com.shop.support.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fraud Detection Service.
 * <ol>
 *   <li>Duy trì số liệu hành vi từ order/payment/user-events (đơn gần đây, hủy, thanh toán lỗi, tuổi tài khoản).</li>
 *   <li>Mỗi OrderCreated → chấm điểm bằng {@link RiskRules} → phát RiskAssessed (và OrderFlagged nếu cần xác minh).</li>
 *   <li>Nhân viên xác minh đơn bị gắn cờ → phát RiskReviewed; Order Service tiếp tục hoặc hủy đơn.</li>
 * </ol>
 */
@Service
public class FraudService {

    private static final Logger log = LoggerFactory.getLogger(FraudService.class);
    static final String CONSUMER = "fraud-service";
    private static final String[] REVIEWERS = {Roles.ADMIN, Roles.ANALYST};

    private final OrderRecordRepository orders;
    private final PaymentFailureRepository paymentFailures;
    private final CustomerProfileRepository customers;
    private final RiskCaseRepository cases;
    private final OutboxService outbox;
    private final IdempotencyGuard guard;
    private final EventReader reader;
    private final ObjectMapper mapper;
    private final FraudProperties props;
    private final RiskRules rules;
    private final AuditTrail audit;

    public FraudService(OrderRecordRepository orders, PaymentFailureRepository paymentFailures,
                        CustomerProfileRepository customers, RiskCaseRepository cases, OutboxService outbox,
                        IdempotencyGuard guard, EventReader reader, ObjectMapper mapper, FraudProperties props,
                        AuditTrail audit) {
        this.audit = audit;
        this.orders = orders;
        this.paymentFailures = paymentFailures;
        this.customers = customers;
        this.cases = cases;
        this.outbox = outbox;
        this.guard = guard;
        this.reader = reader;
        this.mapper = mapper;
        this.props = props;
        this.rules = new RiskRules(props);
    }

    // ------------------------------------------------------------------ Kafka

    @Transactional
    public void handleOrderEvent(EventEnvelope env) {
        switch (env.eventType()) {
            case EventTypes.ORDER_CREATED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    assess(reader.payload(env, OrderCreated.class), occurredAt(env));
                }
            }
            case EventTypes.ORDER_CANCELLED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    OrderStatusChanged p = reader.payload(env, OrderStatusChanged.class);
                    orders.findById(p.orderId()).ifPresent(OrderRecord::markCancelled);
                    cases.findById(p.orderId())
                            .filter(c -> c.getStatus() == RiskCase.Status.PENDING_REVIEW)
                            .ifPresent(c -> c.close("Đơn đã hủy trước khi xác minh: " + p.reason()));
                }
            }
            default -> {
            }
        }
    }

    @Transactional
    public void handlePaymentEvent(EventEnvelope env) {
        if (env.is(EventTypes.PAYMENT_FAILED) && guard.firstTime(env, CONSUMER)) {
            PaymentResult p = reader.payload(env, PaymentResult.class);
            if (!paymentFailures.existsById(p.orderId())) {
                paymentFailures.save(new PaymentFailure(p.orderId(), p.userId(), occurredAt(env)));
            }
        }
    }

    @Transactional
    public void handleUserEvent(EventEnvelope env) {
        if (env.is(EventTypes.USER_REGISTERED)) {
            UserRegistered p = reader.payload(env, UserRegistered.class);
            if (!customers.existsById(p.userId())) {
                customers.save(new CustomerProfile(p.userId(), occurredAt(env)));
            }
        }
    }

    /** Chấm điểm một đơn mới; gọi trong transaction của handler. */
    void assess(OrderCreated p, Instant at) {
        if (orders.existsById(p.orderId())) {
            return; // đã xử lý (sự kiện khác cùng đơn)
        }
        String addressKey = addressKey(p.shippingAddress());
        RiskRules.Input input = new RiskRules.Input(
                p.totalAmount(),
                p.items() == null ? 0 : p.items().stream().mapToInt(OrderLine::quantity).sum(),
                p.items() == null ? 0 : p.items().stream().mapToInt(OrderLine::quantity).max().orElse(0),
                orders.countRecentOrders(p.userId(), at.minus(props.velocityWindow()), p.orderId()),
                orders.countRecentCancellations(p.userId(), at.minus(props.cancelWindow())),
                paymentFailures.countByUserIdAndOccurredAtGreaterThanEqual(p.userId(), at.minus(props.paymentFailureWindow())),
                customers.findById(p.userId()).map(c -> Duration.between(c.getRegisteredAt(), at)).orElse(null),
                addressKey == null ? 0 : orders.countOtherUsersWithAddress(addressKey, p.userId(),
                        at.minus(props.sharedAddressWindow())));
        orders.save(new OrderRecord(p.orderId(), p.userId(), p.totalAmount(), addressKey, at));

        if (Duration.between(at, Instant.now()).compareTo(props.maxEventAge()) > 0) {
            log.debug("OrderCreated #{} quá cũ ({}), chỉ lưu thống kê", p.orderId(), at);
            return;
        }

        RiskRules.Result result = rules.evaluate(input);
        cases.save(new RiskCase(p.orderId(), p.userId(), p.totalAmount(), result.score(), result.level().name(),
                toJson(result.signals()), result.requiresReview()));
        RiskAssessed assessed = new RiskAssessed(p.orderId(), p.userId(), p.totalAmount(), result.score(),
                result.level().name(), result.requiresReview(), result.signals());
        String key = String.valueOf(p.orderId());
        outbox.publish(Topics.FRAUD_ALERT_EVENTS, EventTypes.RISK_ASSESSED, key, assessed);
        if (result.requiresReview()) {
            outbox.publish(Topics.FRAUD_ALERT_EVENTS, EventTypes.ORDER_FLAGGED, key, assessed);
            for (String role : REVIEWERS) {
                outbox.publish(Topics.NOTIFICATION_EVENTS, EventTypes.NOTIFICATION_REQUESTED, "role-" + role,
                        new NotificationRequested(null, role, "ORDER_FLAGGED",
                                "Đơn #" + p.orderId() + " cần xác minh (điểm rủi ro " + result.score() + ")",
                                summary(result.signals()), "fraud:" + p.orderId()));
            }
            log.warn("Đơn #{} bị gắn cờ rủi ro {} điểm: {}", p.orderId(), result.score(), summary(result.signals()));
        }
    }

    // ------------------------------------------------------------------ REST

    @Transactional
    public RiskCaseDto review(AuthenticatedUser user, long orderId, boolean approved, String note) {
        Access.requireAnyRole(user, REVIEWERS);
        RiskCase c = cases.findById(orderId).orElseThrow(() -> ApiException.notFound("Không có hồ sơ rủi ro cho đơn này"));
        if (c.getStatus() != RiskCase.Status.PENDING_REVIEW) {
            throw ApiException.conflict("Hồ sơ đang ở trạng thái " + c.getStatus() + ", không thể xác minh");
        }
        if (!approved && (note == null || note.isBlank())) {
            throw ApiException.badRequest("Vui lòng nhập lý do từ chối");
        }
        String trimmed = note == null ? null : note.trim();
        c.decide(approved, user.email(), trimmed);
        outbox.publish(Topics.FRAUD_ALERT_EVENTS, EventTypes.RISK_REVIEWED, String.valueOf(orderId),
                new RiskReviewed(orderId, c.getUserId(), approved, user.email(), trimmed));
        audit.record(user, approved ? "RISK_APPROVED" : "RISK_REJECTED", "ORDER", orderId,
                "Điểm rủi ro " + c.getScore() + (trimmed == null || trimmed.isBlank() ? "" : ": " + trimmed));
        log.info("Đơn #{} được {} bởi {}", orderId, approved ? "chấp nhận" : "từ chối", user.email());
        return toDto(c);
    }

    @Transactional(readOnly = true)
    public PageResponse<RiskCaseDto> list(AuthenticatedUser user, String status, String level, int page, int size) {
        Access.requireAnyRole(user, REVIEWERS);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        if (status != null && !status.isBlank()) {
            RiskCase.Status s;
            try {
                s = RiskCase.Status.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Trạng thái không hợp lệ: " + status);
            }
            return PageResponse.of(cases.findByStatusOrderByCreatedAtDesc(s, pageable), this::toDto);
        }
        if (level != null && !level.isBlank()) {
            return PageResponse.of(cases.findByLevelOrderByCreatedAtDesc(level, pageable), this::toDto);
        }
        return PageResponse.of(cases.findAllByOrderByCreatedAtDesc(pageable), this::toDto);
    }

    @Transactional(readOnly = true)
    public RiskCaseDto get(AuthenticatedUser user, long orderId) {
        Access.requireAnyRole(user, REVIEWERS);
        return cases.findById(orderId).map(this::toDto)
                .orElseThrow(() -> ApiException.notFound("Không có hồ sơ rủi ro cho đơn này"));
    }

    @Transactional(readOnly = true)
    public SummaryDto summary(AuthenticatedUser user) {
        Access.requireAnyRole(user, REVIEWERS);
        return new SummaryDto(toMap(cases.countByStatus()), toMap(cases.countByLevel()), rules.describe(),
                rules.reviewScore(), rules.minSignalsForReview());
    }

    // ------------------------------------------------------------------ Hỗ trợ

    /** Chuẩn hóa địa chỉ để so khớp: bỏ dấu, ký tự đặc biệt, khoảng trắng thừa, chữ thường. */
    static String addressKey(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String noAccents = Normalizer.normalize(address, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D');
        String key = noAccents.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        return key.length() > 200 ? key.substring(0, 200) : key;
    }

    private RiskCaseDto toDto(RiskCase c) {
        return new RiskCaseDto(c.getOrderId(), c.getUserId(), c.getTotalAmount(), c.getScore(), c.getLevel(),
                c.getStatus().name(), fromJson(c.getSignalsJson()), c.getReviewer(), c.getReviewNote(),
                c.getCreatedAt(), c.getDecidedAt());
    }

    private String toJson(List<RiskSignal> signals) {
        try {
            return mapper.writeValueAsString(signals);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<RiskSignal> fromJson(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String summary(List<RiskSignal> signals) {
        return String.join("; ", signals.stream().map(RiskSignal::detail).toList());
    }

    private static Map<String, Long> toMap(List<Object[]> rows) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Object[] row : rows) {
            map.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return map;
    }

    private static Instant occurredAt(EventEnvelope env) {
        return env.occurredAt() == null ? Instant.now() : env.occurredAt();
    }
}
