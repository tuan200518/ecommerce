package com.shop.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_user", columnList = "userId"),
        @Index(name = "idx_orders_status_created", columnList = "status,createdAt")
})
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 32)
    private String paymentMethod;

    @Column(length = 500)
    private String shippingAddress;

    @Column(length = 500)
    private String cancelReason;

    // Các cột thêm ở phiên bản 2.0 đều cho phép NULL để ddl-auto=update thêm được vào bảng đã có dữ liệu.

    /** Phí vận chuyển (đã cộng vào totalAmount); null với đơn tạo trước khi có tính năng này. */
    @Column(precision = 14, scale = 2)
    private BigDecimal shippingFee;

    /** Inventory đã giữ hàng cho đơn (một trong hai điều kiện để yêu cầu thanh toán). */
    private Boolean inventoryReserved;

    /** Rủi ro đã được chấp nhận (tự động hoặc nhân viên duyệt) – điều kiện còn lại. */
    private Boolean riskCleared;

    private Integer riskScore;

    @Column(length = 16)
    private String riskLevel;

    // Khuyến mãi (phiên bản 2.1) – cũng cho phép NULL. promotionCleared = null với đơn cũ → coi như không cần chờ.

    /** Mã voucher khách nhập (null nếu không dùng). */
    @Column(length = 32)
    private String voucherCode;

    /** Promotion Service đã định giá xong (điều kiện thứ ba để yêu cầu thanh toán). */
    private Boolean promotionCleared;

    /** Giảm giá do flash sale (đã trừ vào totalAmount). */
    @Column(precision = 14, scale = 2)
    private BigDecimal itemDiscount;

    /** Giảm giá do voucher (đã trừ vào totalAmount). */
    @Column(precision = 14, scale = 2)
    private BigDecimal voucherDiscount;

    @Column(length = 255)
    private String promotionSummary;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public Order(long userId, String paymentMethod, String shippingAddress) {
        this.userId = userId;
        this.paymentMethod = paymentMethod;
        this.shippingAddress = shippingAddress;
        this.status = OrderStatus.PENDING;
        this.totalAmount = BigDecimal.ZERO;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void addItem(long productId, String productName, BigDecimal unitPrice, int quantity) {
        items.add(new OrderItem(this, productId, productName, unitPrice, quantity));
        totalAmount = totalAmount.add(unitPrice.multiply(BigDecimal.valueOf(quantity)));
    }

    /** Cộng phí vận chuyển vào tổng tiền (gọi một lần sau khi đã thêm đủ sản phẩm). */
    public void applyShippingFee(BigDecimal fee) {
        this.shippingFee = fee;
        this.totalAmount = totalAmount.add(fee);
    }

    /** Tiền hàng theo giá niêm yết (trước mọi giảm giá). */
    public BigDecimal getSubtotal() {
        return items.stream().map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ------------------------------------------------------------------ Khuyến mãi

    /** Gọi khi tạo đơn: có cần chờ Promotion Service định giá hay không. */
    public void requirePromotion(String voucherCode, boolean required) {
        this.voucherCode = voucherCode;
        this.promotionCleared = !required;
    }

    /**
     * Áp kết quả định giá: giá flash sale từng dòng + giảm giá voucher; tổng tiền = tiền hàng + phí ship − giảm giá.
     *
     * @param salePrices giá sau flash sale theo productId (chỉ các dòng có giảm)
     * @return false nếu đơn đã được định giá trước đó (sự kiện trùng)
     */
    public boolean applyPromotion(java.util.Map<Long, BigDecimal> salePrices, BigDecimal itemDiscount,
                                  BigDecimal voucherDiscount, String appliedVoucher, String summary) {
        if (isPromotionCleared()) {
            return false;
        }
        for (OrderItem item : items) {
            BigDecimal sale = salePrices.get(item.getProductId());
            if (sale != null && sale.compareTo(item.getUnitPrice()) < 0) {
                item.applySalePrice(sale);
            }
        }
        this.itemDiscount = nonNegative(itemDiscount);
        this.voucherDiscount = nonNegative(voucherDiscount);
        this.voucherCode = appliedVoucher == null ? voucherCode : appliedVoucher;
        this.promotionSummary = summary == null || summary.length() <= 255 ? summary : summary.substring(0, 255);
        BigDecimal total = getSubtotal().add(getShippingFee()).subtract(this.itemDiscount).subtract(this.voucherDiscount);
        this.totalAmount = total.max(BigDecimal.ZERO);
        this.promotionCleared = true;
        this.updatedAt = Instant.now();
        return true;
    }

    private static BigDecimal nonNegative(BigDecimal v) {
        return v == null || v.signum() < 0 ? BigDecimal.ZERO : v;
    }

    /** null (đơn tạo trước khi có Promotion Service) → không cần chờ. */
    public boolean isPromotionCleared() {
        return promotionCleared == null || promotionCleared;
    }

    public String getVoucherCode() {
        return voucherCode;
    }

    public BigDecimal getItemDiscount() {
        return itemDiscount == null ? BigDecimal.ZERO : itemDiscount;
    }

    public BigDecimal getVoucherDiscount() {
        return voucherDiscount == null ? BigDecimal.ZERO : voucherDiscount;
    }

    public BigDecimal getDiscount() {
        return getItemDiscount().add(getVoucherDiscount());
    }

    public String getPromotionSummary() {
        return promotionSummary;
    }

    public void markInventoryReserved() {
        this.inventoryReserved = true;
        this.updatedAt = Instant.now();
    }

    public boolean isInventoryReserved() {
        return Boolean.TRUE.equals(inventoryReserved);
    }

    public void recordRisk(int score, String level) {
        this.riskScore = score;
        this.riskLevel = level;
    }

    public void clearRisk() {
        this.riskCleared = true;
    }

    public boolean isRiskCleared() {
        return Boolean.TRUE.equals(riskCleared);
    }

    /** Đủ điều kiện yêu cầu thanh toán: đã giữ hàng, rủi ro đã được chấp nhận và đã định giá khuyến mãi. */
    public boolean readyForPayment() {
        return isInventoryReserved() && isRiskCleared() && isPromotionCleared();
    }

    public BigDecimal getShippingFee() {
        return shippingFee == null ? BigDecimal.ZERO : shippingFee;
    }

    public Integer getRiskScore() {
        return riskScore;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    /** Chuyển trạng thái theo đúng máy trạng thái; trả về trạng thái cũ. */
    public OrderStatus transitionTo(OrderStatus next, String reason) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Không thể chuyển đơn " + id + " từ " + status + " sang " + next);
        }
        OrderStatus previous = status;
        status = next;
        updatedAt = Instant.now();
        if (next == OrderStatus.CANCELLED) {
            cancelReason = reason;
        }
        return previous;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public String getShippingAddress() {
        return shippingAddress;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<OrderItem> getItems() {
        return items;
    }
}
