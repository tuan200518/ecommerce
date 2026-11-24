package com.shop.promotion.service;

import com.shop.promotion.domain.Voucher;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tính giá khuyến mãi – logic thuần (không truy cập CSDL), dùng chung cho báo giá ở checkout và định giá đơn thật.
 * <ol>
 *   <li>Flash sale theo từng dòng: áp giá ưu đãi nếu còn suất và chưa vượt giới hạn mỗi tài khoản.</li>
 *   <li>Voucher (tối đa một mã) tính trên tiền hàng SAU flash sale; FREE_SHIPPING giảm đúng phí vận chuyển.</li>
 * </ol>
 * Mọi điều kiện không thỏa được trả về trong {@code problems}: báo giá hiển thị như cảnh báo, còn khi định giá đơn
 * thật thì bất kỳ vấn đề nào cũng làm đơn bị từ chối (khách không bị tính giá khác với giá đã thấy).
 */
public final class PricingEngine {

    private PricingEngine() {
    }

    public record Line(long productId, String name, int quantity, BigDecimal unitPrice) {
    }

    /**
     * @param remaining        số suất còn lại của chiến dịch
     * @param perUserRemaining số sản phẩm tài khoản này còn được mua với giá flash sale
     */
    public record FlashOffer(long itemId, long campaignId, String campaignName, BigDecimal salePrice, int remaining,
                             int perUserLimit, int perUserRemaining) {
    }

    public record VoucherOffer(long id, String code, Voucher.Type type, BigDecimal value, BigDecimal maxDiscount,
                               BigDecimal minOrderAmount) {
        static VoucherOffer of(Voucher v) {
            return new VoucherOffer(v.getId(), v.getCode(), v.getType(), v.getValue(), v.getMaxDiscount(),
                    v.getMinOrderAmount());
        }
    }

    /** @param offer flash sale đã áp dụng (null nếu dòng này tính giá niêm yết) */
    public record PricedLine(Line line, BigDecimal salePrice, FlashOffer offer) {
        public BigDecimal lineTotal() {
            return salePrice.multiply(BigDecimal.valueOf(line.quantity()));
        }

        public BigDecimal discount() {
            return line.unitPrice().subtract(salePrice).multiply(BigDecimal.valueOf(line.quantity()));
        }
    }

    /**
     * @param voucherApplied voucher hợp lệ và được tính (kể cả khi mức giảm = 0, vd. FREE_SHIPPING cho đơn đã miễn phí)
     */
    public record Result(List<PricedLine> lines, BigDecimal subtotal, BigDecimal itemDiscount,
                         VoucherOffer voucher, boolean voucherApplied, BigDecimal voucherDiscount,
                         String voucherMessage, List<String> problems) {

        public BigDecimal totalDiscount() {
            return itemDiscount.add(voucherDiscount);
        }

        public BigDecimal merchandiseTotal() {
            return subtotal.subtract(itemDiscount);
        }

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /**
     * @param offers        flash sale đang diễn ra theo productId
     * @param voucher       voucher khách nhập (null nếu không nhập hoặc không hợp lệ)
     * @param voucherProblem lý do voucher không dùng được (đã kiểm tra tồn tại/thời hạn/lượt dùng), null nếu hợp lệ
     * @param shippingFee   phí vận chuyển của đơn (cho FREE_SHIPPING)
     */
    public static Result price(List<Line> lines, Map<Long, FlashOffer> offers, VoucherOffer voucher,
                               String voucherProblem, BigDecimal shippingFee) {
        List<String> problems = new ArrayList<>();
        List<PricedLine> priced = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal itemDiscount = BigDecimal.ZERO;

        for (Line line : lines) {
            subtotal = subtotal.add(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())));
            FlashOffer offer = offers.get(line.productId());
            if (offer == null || offer.salePrice().compareTo(line.unitPrice()) >= 0) {
                priced.add(new PricedLine(line, line.unitPrice(), null));
                continue;
            }
            if (line.quantity() > offer.remaining()) {
                problems.add(offer.remaining() == 0
                        ? "\"" + line.name() + "\" đã hết suất flash sale"
                        : "\"" + line.name() + "\" chỉ còn " + offer.remaining() + " suất flash sale");
                priced.add(new PricedLine(line, line.unitPrice(), null));
                continue;
            }
            if (line.quantity() > offer.perUserRemaining()) {
                problems.add("Mỗi khách chỉ được mua tối đa " + offer.perUserLimit() + " \"" + line.name()
                        + "\" giá flash sale" + (offer.perUserRemaining() < offer.perUserLimit()
                        ? " (bạn còn " + offer.perUserRemaining() + ")" : ""));
                priced.add(new PricedLine(line, line.unitPrice(), null));
                continue;
            }
            PricedLine pl = new PricedLine(line, offer.salePrice(), offer);
            priced.add(pl);
            itemDiscount = itemDiscount.add(pl.discount());
        }

        BigDecimal merchandise = subtotal.subtract(itemDiscount);
        BigDecimal voucherDiscount = BigDecimal.ZERO;
        boolean voucherApplied = false;
        String voucherMessage = null;
        if (voucherProblem != null) {
            problems.add(voucherProblem);
            voucherMessage = voucherProblem;
        } else if (voucher != null) {
            if (merchandise.compareTo(voucher.minOrderAmount()) < 0) {
                voucherMessage = "Mã " + voucher.code() + " áp dụng cho đơn từ " + money(voucher.minOrderAmount())
                        + " (còn thiếu " + money(voucher.minOrderAmount().subtract(merchandise)) + ")";
                problems.add(voucherMessage);
            } else {
                voucherApplied = true;
                voucherDiscount = discount(voucher, merchandise, shippingFee);
                voucherMessage = voucherDiscount.signum() > 0
                        ? "Mã " + voucher.code() + " giảm " + money(voucherDiscount)
                        : voucher.type() == Voucher.Type.FREE_SHIPPING
                        ? "Đơn đã được miễn phí vận chuyển – mã " + voucher.code() + " không cần dùng"
                        : "Mã " + voucher.code() + " không mang lại ưu đãi cho đơn này";
            }
        }
        return new Result(priced, subtotal, itemDiscount, voucher, voucherApplied, voucherDiscount, voucherMessage,
                problems);
    }

    /** Mức giảm của voucher (làm tròn xuống đồng, không vượt tiền hàng / phí vận chuyển). */
    static BigDecimal discount(VoucherOffer v, BigDecimal merchandise, BigDecimal shippingFee) {
        BigDecimal d = switch (v.type()) {
            case PERCENT -> {
                BigDecimal raw = merchandise.multiply(v.value()).divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR);
                yield v.maxDiscount() == null ? raw : raw.min(v.maxDiscount());
            }
            case FIXED -> v.value().min(merchandise);
            case FREE_SHIPPING -> shippingFee == null ? BigDecimal.ZERO : shippingFee;
        };
        return d.max(BigDecimal.ZERO).setScale(0, RoundingMode.FLOOR);
    }

    static String money(BigDecimal amount) {
        return NumberFormat.getNumberInstance(Locale.of("vi", "VN")).format(amount) + " ₫";
    }

    /** Mô tả ngắn cho lịch sử đơn hàng. */
    public static String summary(Result r) {
        List<String> parts = new ArrayList<>();
        if (r.itemDiscount().signum() > 0) {
            parts.add("Flash sale -" + money(r.itemDiscount()));
        }
        if (r.voucherApplied() && r.voucherDiscount().signum() > 0) {
            parts.add((r.voucher().type() == Voucher.Type.FREE_SHIPPING ? "Miễn phí vận chuyển (" : "Voucher ")
                    + r.voucher().code() + (r.voucher().type() == Voucher.Type.FREE_SHIPPING ? ") -" : " -")
                    + money(r.voucherDiscount()));
        }
        return parts.isEmpty() ? "Không có ưu đãi" : String.join("; ", parts);
    }
}
