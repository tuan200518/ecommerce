package com.shop.promotion.web;

import com.shop.promotion.domain.CatalogItem;
import com.shop.promotion.domain.FlashSale;
import com.shop.promotion.domain.FlashSaleItem;
import com.shop.promotion.domain.Voucher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class PromotionDtos {

    private PromotionDtos() {
    }

    // ------------------------------------------------------------------ Báo giá (checkout)

    public record QuoteItem(@NotNull Long productId, @Min(1) @Max(1000) int quantity) {
    }

    /** @param shippingFee phí vận chuyển ước tính phía client – chỉ để hiển thị mức giảm của mã FREE_SHIPPING */
    public record QuoteRequest(@NotEmpty @Size(max = 50) List<@Valid QuoteItem> items,
                               @Size(max = 32) @Pattern(regexp = "[A-Za-z0-9_-]*", message = "Mã giảm giá không hợp lệ")
                               String voucherCode,
                               @DecimalMin("0") BigDecimal shippingFee) {
    }

    public record QuoteLineDto(long productId, String name, int quantity, BigDecimal unitPrice, BigDecimal salePrice,
                               BigDecimal lineTotal, String flashSale) {
    }

    /**
     * @param voucherValid    mã hợp lệ và áp dụng được cho đơn này
     * @param freeShipping    mã miễn phí vận chuyển
     * @param warnings        điều kiện chưa thỏa – nếu đặt hàng ngay, đơn sẽ bị hủy với lý do tương ứng
     */
    public record QuoteDto(List<QuoteLineDto> lines, BigDecimal subtotal, BigDecimal itemDiscount,
                           String voucherCode, boolean voucherValid, boolean freeShipping, BigDecimal voucherDiscount,
                           String voucherMessage, BigDecimal totalDiscount, BigDecimal merchandiseTotal,
                           List<String> warnings) {
    }

    // ------------------------------------------------------------------ Flash sale

    public record FlashSaleItemDto(long id, long productId, String productName, String imageUrl, BigDecimal listPrice,
                                   BigDecimal salePrice, int discountPercent, int quota, int sold, int remaining,
                                   int perUserLimit) {
        public static FlashSaleItemDto from(FlashSaleItem i, Map<Long, CatalogItem> catalog) {
            CatalogItem c = catalog.get(i.getProductId());
            BigDecimal list = c == null ? i.getListPrice() : c.getPrice();
            int percent = list.signum() == 0 ? 0 : list.subtract(i.getSalePrice()).multiply(BigDecimal.valueOf(100))
                    .divide(list, 0, RoundingMode.HALF_UP).intValue();
            return new FlashSaleItemDto(i.getId(), i.getProductId(), c == null ? i.getProductName() : c.getName(),
                    c == null ? null : c.getImageUrl(), list, i.getSalePrice(), Math.max(percent, 0), i.getQuota(),
                    i.getSold(), i.remaining(), i.getPerUserLimit());
        }
    }

    public record FlashSaleDto(long id, String name, String description, Instant startsAt, Instant endsAt,
                               String status, String createdBy, List<FlashSaleItemDto> items) {
        public static FlashSaleDto from(FlashSale f, Map<Long, CatalogItem> catalog, Instant now) {
            return new FlashSaleDto(f.getId(), f.getName(), f.getDescription(), f.getStartsAt(), f.getEndsAt(),
                    f.status(now).name(), f.getCreatedBy(),
                    f.getItems().stream().map(i -> FlashSaleItemDto.from(i, catalog)).toList());
        }
    }

    public record FlashSaleItemRequest(@NotNull Long productId, @NotNull @DecimalMin(value = "1000") BigDecimal salePrice,
                                       @Min(1) @Max(100000) int quota, @Min(1) @Max(100) int perUserLimit) {
    }

    public record FlashSaleRequest(@NotBlank @Size(max = 150) String name, @Size(max = 500) String description,
                                   @NotNull Instant startsAt, @NotNull Instant endsAt,
                                   @NotEmpty @Size(max = 50) List<@Valid FlashSaleItemRequest> items) {
    }

    // ------------------------------------------------------------------ Voucher

    public record VoucherDto(long id, String code, String description, String type, BigDecimal value,
                             BigDecimal maxDiscount, BigDecimal minOrderAmount, Instant startsAt, Instant endsAt,
                             Integer usageLimit, int used, Integer remainingUses, int perUserLimit, Long ownerUserId,
                             String source, boolean active, String createdBy, Instant createdAt) {
        public static VoucherDto from(Voucher v) {
            return new VoucherDto(v.getId(), v.getCode(), v.getDescription(), v.getType().name(), v.getValue(),
                    v.getMaxDiscount(), v.getMinOrderAmount(), v.getStartsAt(), v.getEndsAt(), v.getUsageLimit(),
                    v.getUsed(), v.remainingUses(), v.getPerUserLimit(), v.getOwnerUserId(), v.getSource().name(),
                    v.isActive(), v.getCreatedBy(), v.getCreatedAt());
        }
    }

    /** Voucher công khai hiển thị cho khách (không lộ thông tin quản trị). */
    public record PublicVoucherDto(String code, String description, String type, BigDecimal value,
                                   BigDecimal maxDiscount, BigDecimal minOrderAmount, Instant endsAt,
                                   Integer remainingUses, int perUserLimit) {
        public static PublicVoucherDto from(Voucher v) {
            return new PublicVoucherDto(v.getCode(), v.getDescription(), v.getType().name(), v.getValue(),
                    v.getMaxDiscount(), v.getMinOrderAmount(), v.getEndsAt(), v.remainingUses(), v.getPerUserLimit());
        }
    }

    /** @param status AVAILABLE | USED | EXPIRED | DISABLED */
    public record MyVoucherDto(String code, String description, String type, BigDecimal value, BigDecimal maxDiscount,
                               BigDecimal minOrderAmount, Instant endsAt, String source, String status) {
    }

    public record VoucherRequest(
            @NotBlank @Size(min = 3, max = 32)
            @Pattern(regexp = "[A-Za-z0-9_-]+", message = "Mã chỉ gồm chữ, số, '-' và '_'") String code,
            @NotBlank @Size(max = 255) String description,
            @NotBlank @Pattern(regexp = "PERCENT|FIXED|FREE_SHIPPING", message = "Loại voucher không hợp lệ") String type,
            @DecimalMin("0") BigDecimal value,
            @DecimalMin("0") BigDecimal maxDiscount,
            @DecimalMin("0") BigDecimal minOrderAmount,
            @NotNull Instant startsAt, @NotNull Instant endsAt,
            @Min(1) Integer usageLimit,
            @Min(1) @Max(100) Integer perUserLimit) {
    }

    public record StatusRequest(boolean active) {
    }

    // ------------------------------------------------------------------ Thống kê

    public record CountAmountDto(String label, long count, BigDecimal amount) {
    }

    /**
     * @param ordersWithPromotion số đơn có ít nhất một ưu đãi (chưa hủy) trong {@code days} ngày
     */
    public record StatsDto(int days, long ordersWithPromotion, BigDecimal flashSaleDiscount, BigDecimal voucherDiscount,
                           long activeFlashSales, long activeVouchers, List<CountAmountDto> topVouchers,
                           List<FlashSaleItemDto> flashSaleItems) {
    }
}
