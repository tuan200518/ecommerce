package com.shop.loyalty.service;

import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.LoyaltyEvents.PointsChanged;
import com.shop.common.event.payload.LoyaltyEvents.PointsRedeemed;
import com.shop.common.event.payload.LoyaltyEvents.TierChanged;
import com.shop.common.event.payload.NotificationEvents.NotificationRequested;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.event.payload.ReviewEvents.ReviewChanged;
import com.shop.common.event.payload.UserEvents.UserRegistered;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.loyalty.LoyaltyProperties;
import com.shop.loyalty.LoyaltyProperties.Reward;
import com.shop.loyalty.domain.LoyaltyAccount;
import com.shop.loyalty.domain.LoyaltyAccountRepository;
import com.shop.loyalty.domain.PointTransaction;
import com.shop.loyalty.domain.PointTransaction.Type;
import com.shop.loyalty.domain.PointTransactionRepository;
import com.shop.loyalty.domain.Tier;
import com.shop.loyalty.web.LoyaltyDtos.AccountDto;
import com.shop.loyalty.web.LoyaltyDtos.RedeemResultDto;
import com.shop.loyalty.web.LoyaltyDtos.RewardDto;
import com.shop.loyalty.web.LoyaltyDtos.ReferralCheckDto;
import com.shop.loyalty.web.LoyaltyDtos.TierDto;
import com.shop.loyalty.web.LoyaltyDtos.TransactionDto;
import com.shop.support.kafka.EventReader;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Loyalty Service: tích điểm theo đơn hợp lệ (nhân hệ số hạng), thưởng nhiệm vụ (đơn đầu tiên, đánh giá),
 * thưởng giới thiệu bạn bè và trừ lại điểm khi đơn bị hủy/hoàn hoặc đánh giá bị ẩn.
 * <p>
 * Mọi thay đổi là một dòng trong sổ cái {@link PointTransaction}; số dư và hạng được tính lại từ đó.
 */
@Service
public class LoyaltyService {

    private static final Logger log = LoggerFactory.getLogger(LoyaltyService.class);
    static final String CONSUMER = "loyalty-service";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final LoyaltyAccountRepository accounts;
    private final PointTransactionRepository transactions;
    private final OutboxService outbox;
    private final IdempotencyGuard guard;
    private final EventReader reader;
    private final LoyaltyProperties props;

    public LoyaltyService(LoyaltyAccountRepository accounts, PointTransactionRepository transactions,
                          OutboxService outbox, IdempotencyGuard guard, EventReader reader, LoyaltyProperties props) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.outbox = outbox;
        this.guard = guard;
        this.reader = reader;
        this.props = props;
    }

    // ------------------------------------------------------------------ Kafka

    @Transactional
    public void handleUserEvent(EventEnvelope env) {
        if (env.is(EventTypes.USER_REGISTERED) && guard.firstTime(env, CONSUMER)) {
            UserRegistered p = reader.payload(env, UserRegistered.class);
            if (p.roles() != null && !p.roles().isEmpty() && !p.roles().contains(Roles.CUSTOMER)) {
                return; // tài khoản nhân viên không tham gia chương trình
            }
            LoyaltyAccount account = ensureAccount(p.userId());
            if (p.referralCode() != null && account.getReferredBy() == null) {
                accounts.findByReferralCode(p.referralCode().toUpperCase(Locale.ROOT))
                        .filter(referrer -> !referrer.getUserId().equals(p.userId()))
                        .ifPresentOrElse(referrer -> account.setReferredBy(referrer.getUserId()),
                                () -> log.info("Mã giới thiệu {} không hợp lệ (user {})", p.referralCode(), p.userId()));
            }
        }
    }

    @Transactional
    public void handleOrderEvent(EventEnvelope env) {
        switch (env.eventType()) {
            case EventTypes.ORDER_CONFIRMED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    onOrderConfirmed(reader.payload(env, OrderStatusChanged.class), at(env), recent(env));
                }
            }
            case EventTypes.ORDER_CANCELLED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    OrderStatusChanged p = reader.payload(env, OrderStatusChanged.class);
                    reverseAll("order:" + p.orderId(), "Đơn #" + p.orderId() + " bị hủy", at(env), recent(env));
                }
            }
            default -> {
            }
        }
    }

    @Transactional
    public void handleReviewEvent(EventEnvelope env) {
        if (!guard.firstTime(env, CONSUMER)) {
            return;
        }
        ReviewChanged p = reader.payload(env, ReviewChanged.class);
        String ref = "review:" + p.reviewId();
        if (env.is(EventTypes.REVIEW_SUBMITTED)) {
            long today = transactions.countByUserIdAndTypeAndCreatedAtGreaterThanEqual(p.userId(), Type.REVIEW_BONUS,
                    at(env).minus(Duration.ofHours(24)));
            if (today >= props.maxReviewBonusPerDay()) {
                log.info("User {} đã đạt giới hạn thưởng đánh giá trong ngày", p.userId());
                return;
            }
            LoyaltyAccount account = ensureAccount(p.userId());
            award(account, Type.REVIEW_BONUS, props.reviewBonus(), BigDecimal.ZERO, ref,
                    "Thưởng đánh giá sản phẩm", at(env), recent(env));
        } else if (env.is(EventTypes.REVIEW_VISIBILITY_CHANGED) && !p.visible()) {
            reverseAll(ref, "Đánh giá bị ẩn", at(env), recent(env));
        }
    }

    private void onOrderConfirmed(OrderStatusChanged p, Instant at, boolean notify) {
        String ref = "order:" + p.orderId();
        LoyaltyAccount account = ensureAccount(p.userId());
        if (transactions.existsByUserIdAndTypeAndReferenceId(p.userId(), Type.EARN, ref)) {
            return;
        }
        BigDecimal amount = p.totalAmount() == null ? BigDecimal.ZERO : p.totalAmount();
        long points = amount.divide(props.amountPerPoint(), 0, RoundingMode.FLOOR)
                .multiply(account.getTier().multiplier()).setScale(0, RoundingMode.FLOOR).longValue();
        award(account, Type.EARN, points, amount, ref, "Tích điểm đơn #" + p.orderId()
                + " (hạng " + account.getTier().displayName() + " ×" + account.getTier().multiplier() + ")", at, notify);

        if (!account.isFirstOrderRewarded()) {
            account.setFirstOrderRewarded(true);
            award(account, Type.FIRST_ORDER_BONUS, props.firstOrderBonus(), BigDecimal.ZERO, ref,
                    "Nhiệm vụ: hoàn thành đơn hàng đầu tiên", at, notify);
        }
        if (account.getReferredBy() != null && !account.isReferralRewarded()) {
            account.setReferralRewarded(true);
            accounts.findById(account.getReferredBy()).ifPresent(referrer ->
                    award(referrer, Type.REFERRAL_BONUS, props.referralBonus(), BigDecimal.ZERO, ref,
                            "Thưởng giới thiệu: bạn bè hoàn thành đơn đầu tiên", at, notify));
        }
    }

    /** Trừ lại mọi khoản thưởng (của bất kỳ tài khoản nào) gắn với một đơn/đánh giá. */
    private void reverseAll(String ref, String reason, Instant at, boolean notify) {
        List<PointTransaction> originals = transactions.findByReferenceIdAndReversedFalseAndPointsGreaterThan(ref, 0);
        for (PointTransaction original : originals) {
            original.markReversed();
            LoyaltyAccount account = ensureAccount(original.getUserId());
            BigDecimal spend = original.getSpendAmount().negate();
            String reversalRef = "rev:" + original.getId();
            if (transactions.existsByUserIdAndTypeAndReferenceId(account.getUserId(), Type.REVERSAL, reversalRef)) {
                continue;
            }
            award(account, Type.REVERSAL, -original.getPoints(), spend, reversalRef,
                    reason + " – trừ " + original.getPoints() + " điểm (" + original.getType() + ")", at, notify);
            switch (original.getType()) {
                case FIRST_ORDER_BONUS -> account.setFirstOrderRewarded(false);
                // Người được giới thiệu = chủ giao dịch EARN của cùng đơn → cho phép thưởng lại ở đơn hợp lệ sau
                case REFERRAL_BONUS -> originals.stream()
                        .filter(t -> t.getType() == Type.EARN)
                        .findFirst()
                        .flatMap(earn -> accounts.findById(earn.getUserId()))
                        .ifPresent(referee -> referee.setReferralRewarded(false));
                default -> {
                }
            }
        }
    }

    // ------------------------------------------------------------------ REST

    @Transactional
    public AccountDto me(AuthenticatedUser user) {
        LoyaltyAccount account = ensureAccount(user.userId());
        return toDto(account);
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionDto> myTransactions(AuthenticatedUser user, int page, int size) {
        return PageResponse.of(transactions.findByUserIdOrderByIdDesc(user.userId(),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))), TransactionDto::from);
    }

    @Transactional(readOnly = true)
    public ReferralCheckDto checkReferral(String code) {
        return accounts.findByReferralCode(code.trim().toUpperCase(Locale.ROOT))
                .map(a -> new ReferralCheckDto(true, "Mã hợp lệ – bạn bè của bạn sẽ được thưởng khi bạn hoàn thành đơn đầu tiên"))
                .orElse(new ReferralCheckDto(false, "Mã giới thiệu không tồn tại"));
    }

    public List<TierDto> tiers() {
        return Arrays.stream(Tier.values()).map(TierDto::from).toList();
    }

    // ------------------------------------------------------------------ Đổi điểm lấy voucher

    public List<RewardDto> rewards() {
        return props.rewards().stream().map(RewardDto::from).toList();
    }

    /**
     * Trừ điểm (khóa dòng tài khoản để không tiêu trùng số dư) và phát PointsRedeemed;
     * Promotion Service nhận sự kiện và phát voucher cá nhân tương ứng.
     */
    @Transactional
    public RedeemResultDto redeem(AuthenticatedUser user, String rewardId) {
        if (!user.hasRole(Roles.CUSTOMER)) {
            throw ApiException.forbidden("Chỉ tài khoản khách hàng được đổi điểm");
        }
        Reward reward = props.rewards().stream().filter(r -> r.id().equalsIgnoreCase(rewardId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Phần thưởng không tồn tại"));
        ensureAccount(user.userId());
        LoyaltyAccount account = accounts.findForUpdate(user.userId()).orElseThrow();
        if (account.getBalance() < reward.points()) {
            throw ApiException.conflict("Không đủ điểm: cần " + reward.points() + ", hiện có " + account.getBalance());
        }
        String redemptionId = UUID.randomUUID().toString();
        String description = "Đổi " + reward.points() + " điểm lấy: " + reward.name();
        transactions.save(new PointTransaction(account.getUserId(), Type.REDEMPTION, -reward.points(), BigDecimal.ZERO,
                "redeem:" + redemptionId, description, Instant.now()));
        account.apply(-reward.points());
        outbox.publish(Topics.LOYALTY_EVENTS, EventTypes.POINTS_REDEEMED, String.valueOf(account.getUserId()),
                new PointsRedeemed(account.getUserId(), redemptionId, reward.id(), reward.name(), reward.points(),
                        account.getBalance(), reward.voucherType(), reward.value(), reward.maxDiscount(),
                        reward.minOrderAmount(), reward.validDays()));
        log.info("User {} đổi {} điểm lấy {}", user.userId(), reward.points(), reward.id());
        return new RedeemResultDto(redemptionId, reward.name(), reward.points(), account.getBalance(),
                "Đổi thưởng thành công! Voucher sẽ xuất hiện trong mục \"Voucher của tôi\" sau vài giây.");
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountDto> topAccounts(AuthenticatedUser user, int page, int size) {
        Access.requireAnyRole(user, Roles.ADMIN, Roles.ANALYST);
        return PageResponse.of(accounts.findAllByOrderByBalanceDesc(
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))), this::toDto);
    }

    // ------------------------------------------------------------------ Nội bộ

    private void award(LoyaltyAccount account, Type type, long points, BigDecimal spend, String ref,
                       String description, Instant at, boolean notify) {
        if (points == 0 && spend.signum() == 0) {
            return;
        }
        transactions.save(new PointTransaction(account.getUserId(), type, points, spend, ref, description, at));
        account.apply(points);
        outbox.publish(Topics.LOYALTY_EVENTS, points >= 0 ? EventTypes.POINTS_EARNED : EventTypes.POINTS_REVERSED,
                String.valueOf(account.getUserId()),
                new PointsChanged(account.getUserId(), type.name(), points, account.getBalance(),
                        account.getTier().name(), ref, description));
        if (notify && points != 0) {
            notifyUser(account.getUserId(), points > 0 ? "POINTS_EARNED" : "POINTS_REVERSED",
                    (points > 0 ? "+" : "") + points + " điểm thưởng", description + ". Số dư: " + account.getBalance() + " điểm");
        }
        recalculateTier(account, at, notify);
    }

    private void recalculateTier(LoyaltyAccount account, Instant at, boolean notify) {
        Tier next = Tier.forSpend(spendLast12Months(account.getUserId(), at));
        if (next != account.getTier()) {
            Tier previous = account.changeTier(next);
            outbox.publish(Topics.LOYALTY_EVENTS, EventTypes.TIER_CHANGED, String.valueOf(account.getUserId()),
                    new TierChanged(account.getUserId(), previous.name(), next.name()));
            if (notify && next.ordinal() > previous.ordinal()) {
                notifyUser(account.getUserId(), "TIER_UPGRADED", "Chúc mừng! Bạn đã lên hạng " + next.displayName(),
                        "Từ nay mỗi đơn hàng được nhân " + next.multiplier() + " điểm thưởng.");
            }
        }
    }

    private BigDecimal spendLast12Months(long userId, Instant at) {
        Instant now = at.isAfter(Instant.now()) ? at : Instant.now();
        return transactions.spendSince(userId, now.minus(Duration.ofDays(365)));
    }

    LoyaltyAccount ensureAccount(long userId) {
        return accounts.findById(userId).orElseGet(() -> accounts.save(new LoyaltyAccount(userId, newReferralCode(userId))));
    }

    private String newReferralCode(long userId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder("SHOP");
            for (int i = 0; i < 6; i++) {
                sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
            }
            String code = sb.toString();
            if (accounts.findByReferralCode(code).isEmpty()) {
                return code;
            }
        }
        return "SHOP" + Long.toString(userId, 36).toUpperCase(Locale.ROOT) + "X";
    }

    private void notifyUser(long userId, String type, String title, String message) {
        outbox.publish(Topics.NOTIFICATION_EVENTS, EventTypes.NOTIFICATION_REQUESTED, "user-" + userId,
                new NotificationRequested(userId, null, type, title, message, "loyalty"));
    }

    private AccountDto toDto(LoyaltyAccount a) {
        BigDecimal spend = spendLast12Months(a.getUserId(), Instant.now());
        Tier next = a.getTier().next();
        return new AccountDto(a.getUserId(), a.getBalance(), a.getLifetimeEarned(), TierDto.from(a.getTier()),
                spend, next == null ? null : TierDto.from(next),
                next == null ? BigDecimal.ZERO : next.minSpend().subtract(spend).max(BigDecimal.ZERO),
                a.getReferralCode(), a.getReferredBy() != null, accounts.countByReferredBy(a.getUserId()),
                a.isFirstOrderRewarded());
    }

    private static Instant at(EventEnvelope env) {
        return env.occurredAt() == null ? Instant.now() : env.occurredAt();
    }

    private boolean recent(EventEnvelope env) {
        return Duration.between(at(env), Instant.now()).compareTo(props.notifyMaxAge()) <= 0;
    }
}
