package com.shop.common.event.payload;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Payload cho topic promotion-events. */
public final class PromotionEvents {

    /**
     * Giá áp dụng cho một dòng hàng.
     *
     * @param salePrice  giá sau flash sale (bằng unitPrice nếu không có khuyến mãi)
     * @param campaignId chiến dịch flash sale đã áp dụng (null nếu không có)
     */
    public record AppliedLine(long productId, int quantity, BigDecimal unitPrice, BigDecimal salePrice,
                              Long campaignId) {
    }

    /**
     * Kết quả định giá cho một đơn – phát cho MỌI đơn mới, kể cả khi không có ưu đãi nào (discount = 0).
     *
     * @param itemDiscount    tổng giảm do flash sale
     * @param voucherDiscount giảm do mã voucher (với FREE_SHIPPING = phí vận chuyển)
     * @param totalDiscount   itemDiscount + voucherDiscount
     * @param summary         mô tả ngắn cho lịch sử đơn, vd. "Flash sale -120.000 ₫; voucher SALE10 -50.000 ₫"
     */
    public record PromotionApplied(long orderId, long userId, String voucherCode, List<AppliedLine> lines,
                                   BigDecimal itemDiscount, BigDecimal voucherDiscount, BigDecimal totalDiscount,
                                   String summary) {
    }

    /** Đơn có ưu đãi bị hủy: suất flash sale và lượt voucher đã được hoàn lại. */
    public record PromotionReleased(long orderId, long userId, BigDecimal itemDiscount, BigDecimal voucherDiscount,
                                    BigDecimal totalDiscount) {
    }

    /** Không áp dụng được ưu đãi khách đã chọn (voucher hết hạn/hết lượt, hết suất flash sale...). */
    public record PromotionRejected(long orderId, long userId, String voucherCode, String reason) {
    }

    /**
     * Voucher mới được phát cho một khách (đổi điểm loyalty, quà tặng...).
     *
     * @param type PERCENT | FIXED | FREE_SHIPPING
     */
    public record VoucherIssued(long voucherId, String code, Long userId, String type, BigDecimal value,
                                BigDecimal maxDiscount, BigDecimal minOrderAmount, Instant endsAt, String source) {
    }

    private PromotionEvents() {
    }
}
