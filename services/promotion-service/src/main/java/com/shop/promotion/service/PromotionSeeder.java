package com.shop.promotion.service;

import com.shop.promotion.domain.CatalogItem;
import com.shop.promotion.domain.CatalogItemRepository;
import com.shop.promotion.domain.FlashSaleRepository;
import com.shop.promotion.domain.Voucher;
import com.shop.promotion.domain.VoucherRepository;
import com.shop.promotion.web.PromotionDtos.FlashSaleItemRequest;
import com.shop.promotion.web.PromotionDtos.FlashSaleRequest;
import com.shop.promotion.web.PromotionDtos.VoucherRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Dữ liệu mẫu cho demo: 3 voucher công khai và một flash sale khai trương (3 ngày, 4 sản phẩm giá cao, giảm ~20%).
 * Chạy định kỳ vì danh mục sản phẩm đến bất đồng bộ qua product-events; mỗi phần chỉ tạo khi bảng tương ứng còn trống.
 */
@Component
@ConditionalOnProperty(prefix = "shop.promotion", name = "seed-enabled", havingValue = "true", matchIfMissing = true)
public class PromotionSeeder {

    private static final Logger log = LoggerFactory.getLogger(PromotionSeeder.class);
    private static final String SEEDER = "system";

    private final VoucherRepository vouchers;
    private final FlashSaleRepository flashSales;
    private final CatalogItemRepository catalog;
    private final PromotionAdminService admin;
    private volatile boolean done;

    public PromotionSeeder(VoucherRepository vouchers, FlashSaleRepository flashSales, CatalogItemRepository catalog,
                           PromotionAdminService admin) {
        this.vouchers = vouchers;
        this.flashSales = flashSales;
        this.catalog = catalog;
        this.admin = admin;
    }

    @Scheduled(initialDelay = 20_000, fixedDelay = 30_000)
    @Transactional
    public void seed() {
        if (done) {
            return;
        }
        Instant now = Instant.now();
        Instant inAYear = now.plus(Duration.ofDays(365));
        if (vouchers.count() == 0) {
            admin.buildVoucher(new VoucherRequest("WELCOME10", "Giảm 10% tối đa 50.000 ₫ cho đơn từ 200.000 ₫",
                    "PERCENT", new BigDecimal("10"), new BigDecimal("50000"), new BigDecimal("200000"), now, inAYear,
                    null, 1), Voucher.Source.SYSTEM, SEEDER);
            admin.buildVoucher(new VoucherRequest("FREESHIP", "Miễn phí vận chuyển (tối đa 3 lần mỗi tài khoản)",
                    "FREE_SHIPPING", null, null, null, now, inAYear, null, 3), Voucher.Source.SYSTEM, SEEDER);
            admin.buildVoucher(new VoucherRequest("GIAM50K", "Giảm 50.000 ₫ cho đơn từ 500.000 ₫ (500 lượt)",
                    "FIXED", new BigDecimal("50000"), null, new BigDecimal("500000"), now, inAYear, 500, 2),
                    Voucher.Source.SYSTEM, SEEDER);
            log.info("Đã tạo voucher mẫu WELCOME10, FREESHIP, GIAM50K");
        }
        if (flashSales.count() == 0) {
            List<CatalogItem> products = catalog.findByActiveTrueOrderByPriceDesc().stream()
                    .filter(p -> p.getPrice().compareTo(new BigDecimal("200000")) >= 0)
                    .limit(4).toList();
            if (products.size() < 2) {
                return; // chưa nhận đủ danh mục sản phẩm – thử lại ở lần sau
            }
            admin.buildFlashSale(new FlashSaleRequest("Flash Sale khai trương",
                    "Giảm khoảng 20% cho một số sản phẩm nổi bật, số lượng có hạn",
                    now, now.plus(Duration.ofDays(3)),
                    products.stream().map(p -> new FlashSaleItemRequest(p.getProductId(),
                            p.getPrice().multiply(new BigDecimal("0.8")).divide(new BigDecimal("1000"), 0, RoundingMode.DOWN)
                                    .multiply(new BigDecimal("1000")),
                            30, 2)).toList()), SEEDER);
            log.info("Đã tạo flash sale mẫu cho {} sản phẩm", products.size());
        }
        done = true;
    }
}
