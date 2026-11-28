package com.shop.promotion.service;

import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.LoyaltyEvents.PointsRedeemed;
import com.shop.common.event.payload.NotificationEvents.NotificationRequested;
import com.shop.common.event.payload.OrderEvents.OrderCreated;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.event.payload.ProductEvents.ProductChanged;
import com.shop.common.event.payload.PromotionEvents.AppliedLine;
import com.shop.common.event.payload.PromotionEvents.PromotionApplied;
import com.shop.common.event.payload.PromotionEvents.PromotionRejected;
import com.shop.common.event.payload.PromotionEvents.PromotionReleased;
import com.shop.common.event.payload.PromotionEvents.VoucherIssued;
import com.shop.common.security.AuthenticatedUser;
import com.shop.promotion.PromotionProperties;
import com.shop.promotion.domain.CatalogItem;
import com.shop.promotion.domain.CatalogItemRepository;
import com.shop.promotion.domain.FlashSale;
import com.shop.promotion.domain.FlashSaleItem;
import com.shop.promotion.domain.FlashSaleItemRepository;
import com.shop.promotion.domain.FlashSaleRepository;
import com.shop.promotion.domain.Redemption;
import com.shop.promotion.domain.RedemptionRepository;
import com.shop.promotion.domain.Voucher;
import com.shop.promotion.domain.VoucherRepository;
import com.shop.promotion.service.PricingEngine.FlashOffer;
import com.shop.promotion.service.PricingEngine.Line;
import com.shop.promotion.service.PricingEngine.PricedLine;
import com.shop.promotion.service.PricingEngine.Result;
import com.shop.promotion.service.PricingEngine.VoucherOffer;
import com.shop.promotion.web.PromotionDtos.FlashSaleDto;
import com.shop.promotion.web.PromotionDtos.MyVoucherDto;
import com.shop.promotion.web.PromotionDtos.PublicVoucherDto;
import com.shop.promotion.web.PromotionDtos.QuoteDto;
import com.shop.promotion.web.PromotionDtos.QuoteItem;
import com.shop.promotion.web.PromotionDtos.QuoteLineDto;
import com.shop.promotion.web.PromotionDtos.QuoteRequest;
import com.shop.support.kafka.EventReader;
import com.shop.support.outbox.IdempotencyGuard;
import com.shop.support.outbox.OutboxService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bước định giá khuyến mãi trong Saga đặt hàng (song song với giữ hàng và chấm điểm rủi ro):
 * nhận OrderCreated → kiểm tra flash sale / voucher → giữ suất & lượt dùng (nguyên tử) → PromotionApplied
 * (Order Service cập nhật tổng tiền) hoặc PromotionRejected (Order Service hủy đơn). OrderCancelled → hoàn suất/lượt.
 */
@Service
public class PromotionService {

    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);
    static final String CONSUMER = "promotion-service";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Duration UPCOMING_WINDOW = Duration.ofDays(7);

    private final CatalogItemRepository catalog;
    private final FlashSaleRepository flashSales;
    private final FlashSaleItemRepository flashItems;
    private final VoucherRepository vouchers;
    private final RedemptionRepository redemptions;
    private final PromotionLedger ledger;
    private final OutboxService outbox;
    private final IdempotencyGuard guard;
    private final EventReader reader;
    private final PromotionProperties props;
    private final MeterRegistry meters;

    public PromotionService(CatalogItemRepository catalog, FlashSaleRepository flashSales,
                            FlashSaleItemRepository flashItems, VoucherRepository vouchers,
                            RedemptionRepository redemptions, PromotionLedger ledger, OutboxService outbox,
                            IdempotencyGuard guard, EventReader reader, PromotionProperties props,
                            MeterRegistry meters) {
        this.catalog = catalog;
        this.flashSales = flashSales;
        this.flashItems = flashItems;
        this.vouchers = vouchers;
        this.redemptions = redemptions;
        this.ledger = ledger;
        this.outbox = outbox;
        this.guard = guard;
        this.reader = reader;
        this.props = props;
        this.meters = meters;
    }

    // ------------------------------------------------------------------ Kafka

    /** Read-model sản phẩm (upsert theo productId → tự nhiên idempotent). */
    @Transactional
    public void handleProductEvent(EventEnvelope env) {
        ProductChanged p = reader.payload(env, ProductChanged.class);
        CatalogItem item = catalog.findById(p.productId()).orElseGet(() -> new CatalogItem(p.productId()));
        item.update(p.name(), p.price(), p.categoryId(), p.imageUrl(),
                p.active() && !env.is(EventTypes.PRODUCT_DISCONTINUED));
        catalog.save(item);
    }

    @Transactional
    public void handleOrderEvent(EventEnvelope env) {
        switch (env.eventType()) {
            case EventTypes.ORDER_CREATED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    Instant at = at(env);
                    if (Duration.between(at, Instant.now()).compareTo(props.maxEventAge()) > 0) {
                        log.debug("Bỏ qua OrderCreated cũ {} (phát sinh {})", env.aggregateId(), at);
                        return;
                    }
                    evaluate(reader.payload(env, OrderCreated.class), at);
                }
            }
            case EventTypes.ORDER_CANCELLED -> {
                if (guard.firstTime(env, CONSUMER)) {
                    release(reader.payload(env, OrderStatusChanged.class).orderId());
                }
            }
            default -> {
            }
        }
    }

    @Transactional
    public void handleLoyaltyEvent(EventEnvelope env) {
        if (env.is(EventTypes.POINTS_REDEEMED) && guard.firstTime(env, CONSUMER)) {
            issueLoyaltyVoucher(reader.payload(env, PointsRedeemed.class),
                    Duration.between(at(env), Instant.now()).compareTo(props.maxEventAge()) <= 0);
        }
    }

    // ------------------------------------------------------------------ Định giá đơn

    void evaluate(OrderCreated order, Instant at) {
        if (redemptions.existsByOrderId(order.orderId())) {
            return; // đã định giá (lớp bảo vệ thứ hai bên cạnh processed_events)
        }
        long userId = order.userId();
        List<Line> lines = order.items().stream()
                .map(l -> new Line(l.productId(), l.productName(), l.quantity(), l.unitPrice()))
                .toList();
        Instant orderTime = order.createdAt() == null ? at : order.createdAt();
        Map<Long, FlashOffer> offers = flashOffers(userId, lines.stream().map(Line::productId).toList(), orderTime);
        VoucherCheck vc = checkVoucher(order.voucherCode(), userId, orderTime);
        Result result = PricingEngine.price(lines, offers, vc.offer(), vc.problem(), order.shippingFee());
        if (!result.ok()) {
            reject(order, String.join("; ", result.problems()));
            return;
        }

        // Giữ suất / lượt dùng: mỗi bước là một UPDATE có điều kiện; thất bại (bị đơn khác giành mất) → hoàn các
        // bước đã làm trong cùng transaction rồi từ chối đơn.
        List<Runnable> undo = new ArrayList<>();
        List<Redemption> held = new ArrayList<>();
        for (PricedLine pl : result.lines()) {
            FlashOffer o = pl.offer();
            if (o == null) {
                continue;
            }
            int qty = pl.line().quantity();
            if (!ledger.reserveFlash(o.itemId(), qty)) {
                rollback(undo);
                reject(order, "\"" + pl.line().name() + "\" vừa hết suất flash sale");
                return;
            }
            undo.add(() -> ledger.releaseFlash(o.itemId(), qty));
            if (!ledger.incrementUsage(PromotionLedger.flashScope(o.itemId()), userId, qty, o.perUserLimit())) {
                rollback(undo);
                reject(order, "Mỗi khách chỉ được mua tối đa " + o.perUserLimit() + " \"" + pl.line().name()
                        + "\" giá flash sale");
                return;
            }
            undo.add(() -> ledger.decrementUsage(PromotionLedger.flashScope(o.itemId()), userId, qty));
            held.add(new Redemption(order.orderId(), userId, Redemption.Kind.FLASH_SALE, o.itemId(),
                    o.campaignName(), qty, pl.discount()));
        }
        VoucherOffer v = result.voucher();
        if (result.voucherApplied() && result.voucherDiscount().signum() > 0) {
            if (!ledger.useVoucher(v.id())) {
                rollback(undo);
                reject(order, "Mã " + v.code() + " vừa hết lượt sử dụng");
                return;
            }
            undo.add(() -> ledger.releaseVoucher(v.id()));
            if (!ledger.incrementUsage(PromotionLedger.voucherScope(v.id()), userId, 1, vc.perUserLimit())) {
                rollback(undo);
                reject(order, "Bạn đã dùng hết số lượt cho mã " + v.code());
                return;
            }
            held.add(new Redemption(order.orderId(), userId, Redemption.Kind.VOUCHER, v.id(), v.code(), 1,
                    result.voucherDiscount()));
        }
        redemptions.saveAll(held);

        String summary = PricingEngine.summary(result);
        outbox.publish(Topics.PROMOTION_EVENTS, EventTypes.PROMOTION_APPLIED, String.valueOf(order.orderId()),
                new PromotionApplied(order.orderId(), userId, result.voucherApplied() ? v.code() : null,
                        result.lines().stream().map(pl -> new AppliedLine(pl.line().productId(), pl.line().quantity(),
                                pl.line().unitPrice(), pl.salePrice(),
                                pl.offer() == null ? null : pl.offer().campaignId())).toList(),
                        result.itemDiscount(), result.voucherDiscount(), result.totalDiscount(), summary));
        meters.counter("shop.promotion.evaluations", "result",
                result.totalDiscount().signum() > 0 ? "discounted" : "none").increment();
        if (result.totalDiscount().signum() > 0) {
            meters.counter("shop.promotion.discount.amount").increment(result.totalDiscount().doubleValue());
            log.info("Đơn #{}: {}", order.orderId(), summary);
        }
    }

    /** Số tiền VND nguyên đồng (tránh dạng 1E+7 khi serialize BigDecimal đọc từ CSDL). */
    private static BigDecimal whole(BigDecimal v) {
        return v.setScale(0, java.math.RoundingMode.HALF_UP);
    }

    private static void rollback(List<Runnable> undo) {
        List<Runnable> reversed = new ArrayList<>(undo);
        Collections.reverse(reversed);
        reversed.forEach(Runnable::run);
    }

    private void reject(OrderCreated order, String reason) {
        log.info("Từ chối khuyến mãi cho đơn #{}: {}", order.orderId(), reason);
        meters.counter("shop.promotion.evaluations", "result", "rejected").increment();
        outbox.publish(Topics.PROMOTION_EVENTS, EventTypes.PROMOTION_REJECTED, String.valueOf(order.orderId()),
                new PromotionRejected(order.orderId(), order.userId(), order.voucherCode(), reason));
    }

    /** Bù trừ khi đơn bị hủy: trả suất flash sale và lượt dùng voucher. */
    void release(long orderId) {
        BigDecimal item = BigDecimal.ZERO;
        BigDecimal voucher = BigDecimal.ZERO;
        Long userId = null;
        for (Redemption r : redemptions.findByOrderIdAndStatus(orderId, Redemption.Status.RESERVED)) {
            if (r.getKind() == Redemption.Kind.FLASH_SALE) {
                ledger.releaseFlash(r.getRefId(), r.getQuantity());
                ledger.decrementUsage(PromotionLedger.flashScope(r.getRefId()), r.getUserId(), r.getQuantity());
                item = item.add(r.getDiscount());
            } else {
                ledger.releaseVoucher(r.getRefId());
                ledger.decrementUsage(PromotionLedger.voucherScope(r.getRefId()), r.getUserId(), 1);
                voucher = voucher.add(r.getDiscount());
            }
            userId = r.getUserId();
            r.release();
            log.info("Hoàn {} '{}' của đơn #{} bị hủy", r.getKind(), r.getLabel(), orderId);
        }
        if (userId != null) {
            // Analytics trừ lại khoản giảm giá của đơn đã hủy khỏi KPI "giảm giá khuyến mãi"
            outbox.publish(Topics.PROMOTION_EVENTS, EventTypes.PROMOTION_RELEASED, String.valueOf(orderId),
                    new PromotionReleased(orderId, userId, whole(item), whole(voucher), whole(item.add(voucher))));
        }
    }

    // ------------------------------------------------------------------ Kiểm tra điều kiện

    private Map<Long, FlashOffer> flashOffers(long userId, List<Long> productIds, Instant at) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, FlashOffer> offers = new HashMap<>();
        flashItems.findActive(productIds, at).stream()
                .sorted(Comparator.comparing(FlashSaleItem::getSalePrice))
                .forEach(i -> offers.computeIfAbsent(i.getProductId(), id -> {
                    int used = userId <= 0 ? 0 : ledger.usage(PromotionLedger.flashScope(i.getId()), userId);
                    return new FlashOffer(i.getId(), i.getFlashSale().getId(), i.getFlashSale().getName(),
                            i.getSalePrice(), i.remaining(), i.getPerUserLimit(),
                            Math.max(0, i.getPerUserLimit() - used));
                }));
        return offers;
    }

    /** @param perUserLimit giới hạn lượt dùng mỗi tài khoản của voucher hợp lệ */
    record VoucherCheck(VoucherOffer offer, String problem, int perUserLimit) {
    }

    VoucherCheck checkVoucher(String rawCode, long userId, Instant at) {
        if (rawCode == null || rawCode.isBlank()) {
            return new VoucherCheck(null, null, 0);
        }
        String code = rawCode.trim().toUpperCase(Locale.ROOT);
        Voucher v = vouchers.findByCode(code).orElse(null);
        if (v == null || (v.getOwnerUserId() != null && v.getOwnerUserId() != userId)) {
            return new VoucherCheck(null, "Mã giảm giá " + code + " không tồn tại", 0);
        }
        String problem = null;
        if (!v.isActive()) {
            problem = "Mã " + code + " đã ngừng áp dụng";
        } else if (at.isBefore(v.getStartsAt())) {
            problem = "Mã " + code + " chưa đến thời gian áp dụng";
        } else if (!at.isBefore(v.getEndsAt())) {
            problem = "Mã " + code + " đã hết hạn";
        } else if (v.isExhausted()) {
            problem = "Mã " + code + " đã hết lượt sử dụng";
        } else if (ledger.usage(PromotionLedger.voucherScope(v.getId()), userId) >= v.getPerUserLimit()) {
            problem = "Bạn đã dùng hết số lượt cho mã " + code;
        }
        return problem == null
                ? new VoucherCheck(VoucherOffer.of(v), null, v.getPerUserLimit())
                : new VoucherCheck(null, problem, 0);
    }

    // ------------------------------------------------------------------ Voucher đổi từ điểm Loyalty

    void issueLoyaltyVoucher(PointsRedeemed p, boolean notify) {
        if (vouchers.existsBySourceRef(p.redemptionId())) {
            return;
        }
        Voucher.Type type;
        try {
            type = Voucher.Type.valueOf(p.voucherType());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("Loại voucher không hợp lệ trong PointsRedeemed: " + p.voucherType());
        }
        Instant now = Instant.now();
        Voucher v = vouchers.save(new Voucher(newCode("PT"), p.rewardName(), type,
                p.voucherValue() == null ? BigDecimal.ZERO : p.voucherValue(), p.maxDiscount(), p.minOrderAmount(),
                now, now.plus(Duration.ofDays(Math.max(p.validDays(), 1))), 1, 1, p.userId(), Voucher.Source.LOYALTY,
                p.redemptionId(), "loyalty-service"));
        outbox.publish(Topics.PROMOTION_EVENTS, EventTypes.VOUCHER_ISSUED, "user-" + p.userId(),
                new VoucherIssued(v.getId(), v.getCode(), p.userId(), type.name(), v.getValue(), v.getMaxDiscount(),
                        v.getMinOrderAmount(), v.getEndsAt(), Voucher.Source.LOYALTY.name()));
        if (notify) {
            outbox.publish(Topics.NOTIFICATION_EVENTS, EventTypes.NOTIFICATION_REQUESTED, "user-" + p.userId(),
                    new NotificationRequested(p.userId(), null, "VOUCHER_ISSUED", "Bạn nhận được voucher " + v.getCode(),
                            p.rewardName() + " – hạn dùng " + Math.max(p.validDays(), 1) + " ngày. Nhập mã khi thanh toán.",
                            "voucher:" + v.getCode()));
        }
        log.info("Phát voucher {} cho user {} (đổi {} điểm)", v.getCode(), p.userId(), p.points());
    }

    String newCode(String prefix) {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder(prefix);
            for (int i = 0; i < 8; i++) {
                sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
            }
            if (!vouchers.existsByCode(sb.toString())) {
                return sb.toString();
            }
        }
        throw new IllegalStateException("Không sinh được mã voucher duy nhất");
    }

    // ------------------------------------------------------------------ REST cho khách

    /** Báo giá ở checkout: cùng logic với định giá đơn thật nhưng không giữ suất/lượt. */
    @Transactional(readOnly = true)
    public QuoteDto quote(AuthenticatedUser user, QuoteRequest req) {
        Map<Long, Integer> merged = new LinkedHashMap<>();
        for (QuoteItem item : req.items()) {
            merged.merge(item.productId(), item.quantity(), Integer::sum);
        }
        Map<Long, CatalogItem> products = catalog.findAllById(merged.keySet()).stream()
                .collect(Collectors.toMap(CatalogItem::getProductId, Function.identity()));
        List<String> warnings = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        merged.forEach((productId, qty) -> {
            CatalogItem c = products.get(productId);
            if (c == null || !c.isActive()) {
                warnings.add("Sản phẩm #" + productId + " không còn kinh doanh");
            } else {
                lines.add(new Line(productId, c.getName(), qty, c.getPrice()));
            }
        });
        Instant now = Instant.now();
        Map<Long, FlashOffer> offers = flashOffers(user.userId(), lines.stream().map(Line::productId).toList(), now);
        VoucherCheck vc = checkVoucher(req.voucherCode(), user.userId(), now);
        Result r = PricingEngine.price(lines, offers, vc.offer(), vc.problem(), req.shippingFee());
        warnings.addAll(r.problems());
        String code = req.voucherCode() == null || req.voucherCode().isBlank() ? null
                : req.voucherCode().trim().toUpperCase(Locale.ROOT);
        return new QuoteDto(
                r.lines().stream().map(pl -> new QuoteLineDto(pl.line().productId(), pl.line().name(),
                        pl.line().quantity(), pl.line().unitPrice(), pl.salePrice(), pl.lineTotal(),
                        pl.offer() == null ? null : pl.offer().campaignName())).toList(),
                r.subtotal(), r.itemDiscount(), code, r.voucherApplied(),
                r.voucherApplied() && r.voucher().type() == Voucher.Type.FREE_SHIPPING,
                r.voucherDiscount(), r.voucherMessage(), r.totalDiscount(), r.merchandiseTotal(), warnings);
    }

    @Transactional(readOnly = true)
    public List<FlashSaleDto> currentFlashSales() {
        Instant now = Instant.now();
        List<FlashSale> sales = flashSales.findCurrentAndUpcoming(now, now.plus(UPCOMING_WINDOW));
        Map<Long, CatalogItem> products = catalogFor(sales);
        return sales.stream().map(f -> FlashSaleDto.from(f, products, now)).toList();
    }

    @Transactional(readOnly = true)
    public List<PublicVoucherDto> publicVouchers() {
        return vouchers.findPublicAvailable(Instant.now()).stream().limit(20).map(PublicVoucherDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<MyVoucherDto> myVouchers(AuthenticatedUser user) {
        Instant now = Instant.now();
        return vouchers.findTop50ByOwnerUserIdOrderByCreatedAtDesc(user.userId()).stream()
                .map(v -> new MyVoucherDto(v.getCode(), v.getDescription(), v.getType().name(), v.getValue(),
                        v.getMaxDiscount(), v.getMinOrderAmount(), v.getEndsAt(), v.getSource().name(),
                        !v.isActive() ? "DISABLED" : v.isExhausted() ? "USED"
                                : !now.isBefore(v.getEndsAt()) ? "EXPIRED" : "AVAILABLE"))
                .toList();
    }

    Map<Long, CatalogItem> catalogFor(List<FlashSale> sales) {
        List<Long> ids = sales.stream().flatMap(f -> f.getItems().stream()).map(FlashSaleItem::getProductId)
                .distinct().toList();
        return catalog.findAllById(ids).stream()
                .collect(Collectors.toMap(CatalogItem::getProductId, Function.identity()));
    }

    private static Instant at(EventEnvelope env) {
        return env.occurredAt() == null ? Instant.now() : env.occurredAt();
    }
}
