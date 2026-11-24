package com.shop.promotion.service;

import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.promotion.PromotionProperties;
import com.shop.promotion.domain.CatalogItem;
import com.shop.promotion.domain.CatalogItemRepository;
import com.shop.promotion.domain.FlashSale;
import com.shop.promotion.domain.FlashSaleRepository;
import com.shop.promotion.domain.Redemption;
import com.shop.promotion.domain.RedemptionRepository;
import com.shop.promotion.domain.Voucher;
import com.shop.promotion.domain.VoucherRepository;
import com.shop.promotion.web.PromotionDtos.CountAmountDto;
import com.shop.promotion.web.PromotionDtos.FlashSaleDto;
import com.shop.promotion.web.PromotionDtos.FlashSaleItemDto;
import com.shop.promotion.web.PromotionDtos.FlashSaleItemRequest;
import com.shop.promotion.web.PromotionDtos.FlashSaleRequest;
import com.shop.promotion.web.PromotionDtos.StatsDto;
import com.shop.promotion.web.PromotionDtos.VoucherDto;
import com.shop.promotion.web.PromotionDtos.VoucherRequest;
import com.shop.support.audit.AuditTrail;
import com.shop.support.web.Access;
import com.shop.support.web.ApiException;
import com.shop.support.web.PageResponse;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Quản trị chiến dịch flash sale và voucher. Ghi: ADMIN / PRODUCT_MANAGER; xem: thêm ANALYST. Mọi thao tác ghi có audit. */
@Service
public class PromotionAdminService {

    private static final String[] WRITERS = {Roles.ADMIN, Roles.PRODUCT_MANAGER};
    private static final String[] READERS = {Roles.ADMIN, Roles.PRODUCT_MANAGER, Roles.ANALYST};

    private final FlashSaleRepository flashSales;
    private final VoucherRepository vouchers;
    private final RedemptionRepository redemptions;
    private final CatalogItemRepository catalog;
    private final PromotionService promotions;
    private final AuditTrail audit;
    private final PromotionProperties props;

    public PromotionAdminService(FlashSaleRepository flashSales, VoucherRepository vouchers,
                                 RedemptionRepository redemptions, CatalogItemRepository catalog,
                                 PromotionService promotions, AuditTrail audit, PromotionProperties props) {
        this.flashSales = flashSales;
        this.vouchers = vouchers;
        this.redemptions = redemptions;
        this.catalog = catalog;
        this.promotions = promotions;
        this.audit = audit;
        this.props = props;
    }

    // ------------------------------------------------------------------ Flash sale

    @Transactional(readOnly = true)
    public PageResponse<FlashSaleDto> listFlashSales(AuthenticatedUser user, int page, int size) {
        Access.requireAnyRole(user, READERS);
        var result = flashSales.findAllByOrderByStartsAtDesc(pageable(page, size));
        Map<Long, CatalogItem> products = promotions.catalogFor(result.getContent());
        Instant now = Instant.now();
        return PageResponse.of(result, f -> FlashSaleDto.from(f, products, now));
    }

    @Transactional
    public FlashSaleDto createFlashSale(AuthenticatedUser user, FlashSaleRequest req) {
        Access.requireAnyRole(user, WRITERS);
        FlashSale sale = buildFlashSale(req, user.email());
        audit.record(user, "FLASH_SALE_CREATED", "FLASH_SALE", sale.getId(), sale.getName() + " ("
                + sale.getItems().size() + " sản phẩm, " + sale.getStartsAt() + " → " + sale.getEndsAt() + ")");
        return FlashSaleDto.from(sale, promotions.catalogFor(List.of(sale)), Instant.now());
    }

    /** Dùng chung cho API quản trị và dữ liệu mẫu. */
    FlashSale buildFlashSale(FlashSaleRequest req, String createdBy) {
        Instant now = Instant.now();
        if (!req.endsAt().isAfter(req.startsAt())) {
            throw ApiException.badRequest("Thời gian kết thúc phải sau thời gian bắt đầu");
        }
        if (!req.endsAt().isAfter(now)) {
            throw ApiException.badRequest("Chiến dịch đã kết thúc trong quá khứ");
        }
        if (Duration.between(req.startsAt(), req.endsAt()).compareTo(Duration.ofDays(props.maxFlashSaleDays())) > 0) {
            throw ApiException.badRequest("Flash sale dài tối đa " + props.maxFlashSaleDays() + " ngày");
        }
        Set<Long> seen = new HashSet<>();
        for (FlashSaleItemRequest item : req.items()) {
            if (!seen.add(item.productId())) {
                throw ApiException.badRequest("Sản phẩm #" + item.productId() + " bị lặp trong chiến dịch");
            }
        }
        List<Long> overlapping = flashSales.findOverlappingProducts(seen, req.startsAt(), req.endsAt());
        if (!overlapping.isEmpty()) {
            throw ApiException.conflict("Sản phẩm " + overlapping + " đã có flash sale khác trong khung giờ này");
        }
        FlashSale sale = new FlashSale(req.name().trim(), req.description(), req.startsAt(), req.endsAt(), createdBy);
        for (FlashSaleItemRequest item : req.items()) {
            CatalogItem product = catalog.findById(item.productId())
                    .filter(CatalogItem::isActive)
                    .orElseThrow(() -> ApiException.badRequest("Sản phẩm #" + item.productId() + " không tồn tại hoặc đã ngừng bán"));
            if (item.salePrice().compareTo(product.getPrice()) >= 0) {
                throw ApiException.badRequest("Giá flash sale của \"" + product.getName() + "\" phải thấp hơn giá bán "
                        + PricingEngine.money(product.getPrice()));
            }
            if (item.perUserLimit() > item.quota()) {
                throw ApiException.badRequest("Giới hạn mỗi khách không được lớn hơn tổng số suất");
            }
            sale.addItem(product, item.salePrice(), item.quota(), item.perUserLimit());
        }
        return flashSales.save(sale);
    }

    @Transactional
    public FlashSaleDto cancelFlashSale(AuthenticatedUser user, long id) {
        Access.requireAnyRole(user, WRITERS);
        FlashSale sale = flashSales.findById(id).orElseThrow(() -> ApiException.notFound("Không tìm thấy chiến dịch"));
        Instant now = Instant.now();
        if (sale.status(now) == FlashSale.Status.ENDED || sale.isCancelled()) {
            throw ApiException.conflict("Chiến dịch đã kết thúc hoặc đã hủy");
        }
        sale.cancel();
        audit.record(user, "FLASH_SALE_CANCELLED", "FLASH_SALE", id, sale.getName());
        return FlashSaleDto.from(sale, promotions.catalogFor(List.of(sale)), now);
    }

    // ------------------------------------------------------------------ Voucher

    @Transactional(readOnly = true)
    public PageResponse<VoucherDto> listVouchers(AuthenticatedUser user, String q, String source, int page, int size) {
        Access.requireAnyRole(user, READERS);
        Voucher.Source s = null;
        if (source != null && !source.isBlank()) {
            try {
                s = Voucher.Source.valueOf(source);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Nguồn voucher không hợp lệ: " + source);
            }
        }
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        Voucher.Source sourceFilter = s;
        Specification<Voucher> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (like != null) {
                ps.add(cb.or(cb.like(cb.lower(root.get("code")), like, '\\'),
                        cb.like(cb.lower(root.get("description")), like, '\\')));
            }
            if (sourceFilter != null) {
                ps.add(cb.equal(root.get("source"), sourceFilter));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        return PageResponse.of(vouchers.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"))), VoucherDto::from);
    }

    @Transactional
    public VoucherDto createVoucher(AuthenticatedUser user, VoucherRequest req) {
        Access.requireAnyRole(user, WRITERS);
        Voucher v = buildVoucher(req, Voucher.Source.ADMIN, user.email());
        audit.record(user, "VOUCHER_CREATED", "VOUCHER", v.getId(), v.getCode() + " – " + v.getDescription());
        return VoucherDto.from(v);
    }

    Voucher buildVoucher(VoucherRequest req, Voucher.Source source, String createdBy) {
        String code = req.code().trim().toUpperCase(Locale.ROOT);
        if (vouchers.existsByCode(code)) {
            throw ApiException.conflict("Mã " + code + " đã tồn tại");
        }
        if (!req.endsAt().isAfter(req.startsAt())) {
            throw ApiException.badRequest("Thời gian kết thúc phải sau thời gian bắt đầu");
        }
        Voucher.Type type = Voucher.Type.valueOf(req.type());
        BigDecimal value = req.value() == null ? BigDecimal.ZERO : req.value();
        switch (type) {
            case PERCENT -> {
                if (value.compareTo(BigDecimal.ONE) < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw ApiException.badRequest("Phần trăm giảm phải từ 1 đến 100");
                }
            }
            case FIXED -> {
                if (value.compareTo(BigDecimal.valueOf(1000)) < 0) {
                    throw ApiException.badRequest("Số tiền giảm tối thiểu 1.000 ₫");
                }
            }
            case FREE_SHIPPING -> value = BigDecimal.ZERO;
        }
        return vouchers.save(new Voucher(code, req.description().trim(), type, value,
                type == Voucher.Type.PERCENT ? req.maxDiscount() : null, req.minOrderAmount(), req.startsAt(),
                req.endsAt(), req.usageLimit(), req.perUserLimit() == null ? 1 : req.perUserLimit(), null, source,
                null, createdBy));
    }

    @Transactional
    public VoucherDto setVoucherActive(AuthenticatedUser user, long id, boolean active) {
        Access.requireAnyRole(user, WRITERS);
        Voucher v = vouchers.findById(id).orElseThrow(() -> ApiException.notFound("Không tìm thấy voucher"));
        if (v.isActive() != active) {
            v.setActive(active);
            audit.record(user, active ? "VOUCHER_ENABLED" : "VOUCHER_DISABLED", "VOUCHER", id, v.getCode());
        }
        return VoucherDto.from(v);
    }

    // ------------------------------------------------------------------ Thống kê

    @Transactional(readOnly = true)
    public StatsDto stats(AuthenticatedUser user, int days) {
        Access.requireAnyRole(user, READERS);
        int d = Math.min(Math.max(days, 1), 365);
        Instant now = Instant.now();
        Instant since = now.minus(Duration.ofDays(d));
        BigDecimal flash = BigDecimal.ZERO;
        BigDecimal voucher = BigDecimal.ZERO;
        for (Object[] row : redemptions.totalsByKind(since)) {
            BigDecimal amount = new BigDecimal(row[2].toString());
            if (row[0] == Redemption.Kind.FLASH_SALE || "FLASH_SALE".equals(row[0].toString())) {
                flash = amount;
            } else {
                voucher = amount;
            }
        }
        List<FlashSale> current = flashSales.findCurrentAndUpcoming(now, now.plus(Duration.ofDays(7)));
        Map<Long, CatalogItem> products = promotions.catalogFor(current);
        List<FlashSaleItemDto> items = current.stream().filter(f -> f.status(now) == FlashSale.Status.ACTIVE)
                .flatMap(f -> f.getItems().stream()).map(i -> FlashSaleItemDto.from(i, products)).toList();
        return new StatsDto(d, redemptions.countOrders(since), flash, voucher,
                current.stream().filter(f -> f.status(now) == FlashSale.Status.ACTIVE).count(),
                vouchers.findPublicAvailable(now).size(),
                redemptions.topVouchers(since).stream().limit(10)
                        .map(r -> new CountAmountDto((String) r[0], ((Number) r[1]).longValue(),
                                new BigDecimal(r[2].toString())))
                        .toList(),
                items);
    }

    private static PageRequest pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }
}
