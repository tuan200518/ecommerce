package com.shop.fraud.service;

import com.shop.common.event.payload.FraudEvents.RiskSignal;
import com.shop.fraud.FraudProperties;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bộ luật chấm điểm rủi ro (rule-based, giải thích được). Hàm thuần: nhận số liệu đã tổng hợp và trả về
 * danh sách tín hiệu kèm lý do, nên dễ kiểm thử và dễ trình bày cho nhân viên xác minh.
 * <p>
 * Không luật đơn lẻ nào đủ để đưa đơn vào diện xác minh: cần tổng điểm cao VÀ nhiều tín hiệu
 * (không kết luận gian lận chỉ dựa trên một dấu hiệu). Quyết định cuối cùng luôn do nhân viên.
 */
public class RiskRules {

    public enum Level { LOW, MEDIUM, HIGH }

    /** Số liệu đầu vào cho một đơn mới. accountAge null nếu chưa biết thời điểm đăng ký. */
    public record Input(BigDecimal totalAmount, int totalQuantity, int maxLineQuantity, long recentOrders,
                        long recentCancellations, long recentPaymentFailures, Duration accountAge,
                        long otherUsersSameAddress) {
    }

    public record Result(int score, Level level, boolean requiresReview, List<RiskSignal> signals) {
    }

    /** Mô tả luật cho màn hình quản trị. */
    public record RuleInfo(String code, String description, int points) {
    }

    static final int HIGH_VALUE_POINTS = 30;
    static final int VERY_HIGH_VALUE_POINTS = 50;
    static final int VELOCITY_POINTS = 30;
    static final int CANCEL_POINTS = 20;
    static final int PAYMENT_FAILURE_POINTS = 25;
    static final int NEW_ACCOUNT_POINTS = 25;
    static final int BULK_POINTS = 20;
    static final int SHARED_ADDRESS_POINTS = 25;

    private final FraudProperties p;

    public RiskRules(FraudProperties props) {
        this.p = props;
    }

    public Result evaluate(Input in) {
        List<RiskSignal> signals = new ArrayList<>();
        if (in.totalAmount().compareTo(p.veryHighValueAmount()) >= 0) {
            signals.add(new RiskSignal("VERY_HIGH_VALUE", VERY_HIGH_VALUE_POINTS,
                    "Giá trị đơn " + money(in.totalAmount()) + " ≥ " + money(p.veryHighValueAmount())));
        } else if (in.totalAmount().compareTo(p.highValueAmount()) >= 0) {
            signals.add(new RiskSignal("HIGH_VALUE", HIGH_VALUE_POINTS,
                    "Giá trị đơn " + money(in.totalAmount()) + " ≥ " + money(p.highValueAmount())));
        }
        if (in.recentOrders() >= p.velocityOrders()) {
            signals.add(new RiskSignal("VELOCITY", VELOCITY_POINTS,
                    "Đã đặt " + in.recentOrders() + " đơn khác trong " + duration(p.velocityWindow())));
        }
        if (in.recentCancellations() >= p.cancelledOrders()) {
            signals.add(new RiskSignal("CANCEL_HISTORY", CANCEL_POINTS,
                    in.recentCancellations() + " đơn bị hủy trong " + duration(p.cancelWindow())));
        }
        if (in.recentPaymentFailures() >= p.paymentFailures()) {
            signals.add(new RiskSignal("PAYMENT_FAILURES", PAYMENT_FAILURE_POINTS,
                    in.recentPaymentFailures() + " lần thanh toán thất bại trong " + duration(p.paymentFailureWindow())));
        }
        if (in.accountAge() != null && in.accountAge().compareTo(p.newAccountAge()) < 0
                && in.totalAmount().compareTo(p.newAccountAmount()) >= 0) {
            signals.add(new RiskSignal("NEW_ACCOUNT", NEW_ACCOUNT_POINTS,
                    "Tài khoản mới tạo " + duration(in.accountAge()) + " đã đặt đơn " + money(in.totalAmount())));
        }
        if (in.totalQuantity() >= p.bulkQuantity()) {
            signals.add(new RiskSignal("BULK_QUANTITY", BULK_POINTS,
                    "Số lượng lớn bất thường: " + in.totalQuantity() + " sản phẩm (dòng lớn nhất " + in.maxLineQuantity() + ")"));
        }
        if (in.otherUsersSameAddress() >= p.sharedAddressUsers()) {
            signals.add(new RiskSignal("SHARED_ADDRESS", SHARED_ADDRESS_POINTS,
                    "Địa chỉ giao hàng được " + in.otherUsersSameAddress() + " tài khoản khác sử dụng gần đây"));
        }

        int score = Math.min(100, signals.stream().mapToInt(RiskSignal::points).sum());
        boolean review = score >= p.reviewScore() && signals.size() >= p.minSignalsForReview();
        Level level = review ? Level.HIGH : score >= p.mediumScore() ? Level.MEDIUM : Level.LOW;
        return new Result(score, level, review, List.copyOf(signals));
    }

    public List<RuleInfo> describe() {
        return List.of(
                new RuleInfo("HIGH_VALUE", "Giá trị đơn ≥ " + money(p.highValueAmount()), HIGH_VALUE_POINTS),
                new RuleInfo("VERY_HIGH_VALUE", "Giá trị đơn ≥ " + money(p.veryHighValueAmount())
                        + " (thay cho HIGH_VALUE)", VERY_HIGH_VALUE_POINTS),
                new RuleInfo("VELOCITY", "≥ " + p.velocityOrders() + " đơn khác trong "
                        + duration(p.velocityWindow()), VELOCITY_POINTS),
                new RuleInfo("CANCEL_HISTORY", "≥ " + p.cancelledOrders() + " đơn bị hủy trong "
                        + duration(p.cancelWindow()), CANCEL_POINTS),
                new RuleInfo("PAYMENT_FAILURES", "≥ " + p.paymentFailures() + " lần thanh toán lỗi trong "
                        + duration(p.paymentFailureWindow()), PAYMENT_FAILURE_POINTS),
                new RuleInfo("NEW_ACCOUNT", "Tài khoản dưới " + duration(p.newAccountAge()) + " đặt đơn ≥ "
                        + money(p.newAccountAmount()), NEW_ACCOUNT_POINTS),
                new RuleInfo("BULK_QUANTITY", "Tổng số lượng sản phẩm ≥ " + p.bulkQuantity(), BULK_POINTS),
                new RuleInfo("SHARED_ADDRESS", "Địa chỉ được ≥ " + p.sharedAddressUsers() + " tài khoản khác dùng trong "
                        + duration(p.sharedAddressWindow()), SHARED_ADDRESS_POINTS));
    }

    public int reviewScore() {
        return p.reviewScore();
    }

    public int minSignalsForReview() {
        return p.minSignalsForReview();
    }

    private static String money(BigDecimal v) {
        return NumberFormat.getNumberInstance(Locale.of("vi", "VN")).format(v) + " ₫";
    }

    static String duration(Duration d) {
        long m = d.toMinutes();
        if (m >= 1440 && m % 1440 == 0) {
            return (m / 1440) + " ngày";
        }
        if (m >= 60 && m % 60 == 0) {
            return (m / 60) + " giờ";
        }
        return Math.max(m, 0) + " phút";
    }
}
