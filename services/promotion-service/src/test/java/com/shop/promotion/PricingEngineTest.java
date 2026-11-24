package com.shop.promotion;

import com.shop.promotion.domain.Voucher;
import com.shop.promotion.service.PricingEngine;
import com.shop.promotion.service.PricingEngine.FlashOffer;
import com.shop.promotion.service.PricingEngine.Line;
import com.shop.promotion.service.PricingEngine.Result;
import com.shop.promotion.service.PricingEngine.VoucherOffer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PricingEngineTest {

    private static final Line PHONE = new Line(1, "Điện thoại", 1, new BigDecimal("10000000"));
    private static final Line CASE = new Line(2, "Ốp lưng", 2, new BigDecimal("150000"));

    private static FlashOffer offer(String salePrice, int remaining, int perUserLimit, int perUserRemaining) {
        return new FlashOffer(11, 5, "Flash 11.11", new BigDecimal(salePrice), remaining, perUserLimit, perUserRemaining);
    }

    private static VoucherOffer voucher(Voucher.Type type, String value, String max, String min) {
        return new VoucherOffer(7, "SALE", type, new BigDecimal(value), max == null ? null : new BigDecimal(max),
                new BigDecimal(min));
    }

    @Test
    void flashSale_appliesSalePrice_andVoucherIsComputedOnDiscountedTotal() {
        Result r = PricingEngine.price(List.of(PHONE, CASE), Map.of(1L, offer("8000000", 10, 2, 2)),
                voucher(Voucher.Type.PERCENT, "10", "500000", "1000000"), null, BigDecimal.ZERO);
        assertThat(r.ok()).isTrue();
        assertThat(r.subtotal()).isEqualByComparingTo("10300000");
        assertThat(r.itemDiscount()).isEqualByComparingTo("2000000");
        // 10% của 8.300.000 = 830.000 nhưng tối đa 500.000
        assertThat(r.voucherDiscount()).isEqualByComparingTo("500000");
        assertThat(r.totalDiscount()).isEqualByComparingTo("2500000");
        assertThat(PricingEngine.summary(r)).contains("Flash sale").contains("Voucher SALE");
    }

    @Test
    void flashSale_problems_whenSoldOutOrPerUserLimitExceeded() {
        Line two = new Line(1, "Điện thoại", 2, new BigDecimal("10000000"));
        Result soldOut = PricingEngine.price(List.of(two), Map.of(1L, offer("8000000", 1, 5, 5)), null, null, null);
        assertThat(soldOut.problems()).singleElement().asString().contains("chỉ còn 1 suất");
        assertThat(soldOut.itemDiscount()).isEqualByComparingTo("0");

        Result limit = PricingEngine.price(List.of(two), Map.of(1L, offer("8000000", 50, 2, 1)), null, null, null);
        assertThat(limit.problems()).singleElement().asString().contains("tối đa 2").contains("bạn còn 1");

        // Giá flash sale không thấp hơn giá trên đơn (giá sản phẩm vừa giảm) → không áp dụng, không báo lỗi
        Result ignored = PricingEngine.price(List.of(PHONE), Map.of(1L, offer("12000000", 10, 2, 2)), null, null, null);
        assertThat(ignored.ok()).isTrue();
        assertThat(ignored.itemDiscount()).isEqualByComparingTo("0");
    }

    @Test
    void voucherTypes_andMinimumOrder() {
        Result fixed = PricingEngine.price(List.of(CASE), Map.of(), voucher(Voucher.Type.FIXED, "500000", null, "0"),
                null, BigDecimal.ZERO);
        assertThat(fixed.voucherDiscount()).isEqualByComparingTo("300000"); // không vượt tiền hàng

        Result ship = PricingEngine.price(List.of(CASE), Map.of(), voucher(Voucher.Type.FREE_SHIPPING, "0", null, "0"),
                null, new BigDecimal("30000"));
        assertThat(ship.voucherDiscount()).isEqualByComparingTo("30000");

        Result alreadyFree = PricingEngine.price(List.of(PHONE), Map.of(),
                voucher(Voucher.Type.FREE_SHIPPING, "0", null, "0"), null, BigDecimal.ZERO);
        assertThat(alreadyFree.ok()).isTrue();
        assertThat(alreadyFree.voucherApplied()).isTrue();
        assertThat(alreadyFree.voucherDiscount()).isEqualByComparingTo("0");

        Result tooSmall = PricingEngine.price(List.of(CASE), Map.of(), voucher(Voucher.Type.PERCENT, "10", null, "500000"),
                null, BigDecimal.ZERO);
        assertThat(tooSmall.ok()).isFalse();
        assertThat(tooSmall.voucherMessage()).contains("còn thiếu 200.000");

        Result invalid = PricingEngine.price(List.of(CASE), Map.of(), null, "Mã giảm giá X không tồn tại", null);
        assertThat(invalid.problems()).containsExactly("Mã giảm giá X không tồn tại");
    }

    @Test
    void percentDiscount_isRoundedDownToWholeDong() {
        Line odd = new Line(3, "Bút", 1, new BigDecimal("33333"));
        Result r = PricingEngine.price(List.of(odd), Map.of(), voucher(Voucher.Type.PERCENT, "15", null, "0"), null, null);
        assertThat(r.voucherDiscount()).isEqualByComparingTo("4999");
    }
}
