package com.shop.order.web;

import com.shop.order.domain.Order;
import com.shop.order.domain.OrderItem;
import com.shop.order.domain.OrderStatusHistory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateOrderRequest(
            @NotEmpty(message = "Đơn hàng phải có ít nhất 1 sản phẩm") @Size(max = 50) List<@Valid ItemRequest> items,
            @NotBlank(message = "Vui lòng nhập địa chỉ giao hàng") @Size(max = 500) String shippingAddress,
            @NotBlank @Pattern(regexp = "MOCK_CARD|MOCK_WALLET|MOCK_FAIL", message = "Phương thức thanh toán không hợp lệ")
            String paymentMethod,
            @Size(max = 32) @Pattern(regexp = "[A-Za-z0-9_-]*", message = "Mã giảm giá không hợp lệ")
            String voucherCode) {

        public CreateOrderRequest(List<ItemRequest> items, String shippingAddress, String paymentMethod) {
            this(items, shippingAddress, paymentMethod, null);
        }
    }

    public record ItemRequest(@NotNull Long productId, @Min(1) @Max(1000) int quantity) {
    }

    public record UpdateStatusRequest(@NotBlank String status, @Size(max = 500) String note) {
    }

    /** @param salePrice giá flash sale (null nếu không có); lineTotal tính theo giá thực trả */
    public record OrderItemDto(long productId, String productName, BigDecimal unitPrice, int quantity,
                               BigDecimal lineTotal, BigDecimal salePrice) {
        static OrderItemDto from(OrderItem i) {
            return new OrderItemDto(i.getProductId(), i.getProductName(), i.getUnitPrice(), i.getQuantity(),
                    i.getEffectivePrice().multiply(BigDecimal.valueOf(i.getQuantity())), i.getSalePrice());
        }
    }

    public record HistoryDto(String fromStatus, String toStatus, String note, Instant changedAt) {
        static HistoryDto from(OrderStatusHistory h) {
            return new HistoryDto(h.getFromStatus() == null ? null : h.getFromStatus().name(),
                    h.getToStatus().name(), h.getNote(), h.getChangedAt());
        }
    }

    /**
     * @param riskScore / riskLevel chỉ trả về cho nhân viên (null với khách hàng).
     * @param subtotal          tiền hàng theo giá niêm yết
     * @param itemDiscount      giảm do flash sale; voucherDiscount: giảm do mã voucher
     * @param promotionPending  Promotion Service chưa định giá xong → tổng tiền có thể còn giảm
     */
    public record OrderDto(long id, long userId, String status, BigDecimal subtotal, BigDecimal shippingFee,
                           BigDecimal totalAmount, String paymentMethod,
                           String shippingAddress, String cancelReason, Instant createdAt, Instant updatedAt,
                           List<OrderItemDto> items, List<HistoryDto> history, Integer riskScore, String riskLevel,
                           String voucherCode, BigDecimal itemDiscount, BigDecimal voucherDiscount,
                           BigDecimal discount, String promotionSummary, boolean promotionPending) {

        public static OrderDto from(Order o, List<OrderStatusHistory> history, boolean withRisk) {
            return new OrderDto(o.getId(), o.getUserId(), o.getStatus().name(), o.getSubtotal(), o.getShippingFee(),
                    o.getTotalAmount(), o.getPaymentMethod(), o.getShippingAddress(), o.getCancelReason(),
                    o.getCreatedAt(), o.getUpdatedAt(),
                    o.getItems().stream().map(OrderItemDto::from).toList(),
                    history == null ? null : history.stream().map(HistoryDto::from).toList(),
                    withRisk ? o.getRiskScore() : null, withRisk ? o.getRiskLevel() : null,
                    o.getVoucherCode(), o.getItemDiscount(), o.getVoucherDiscount(), o.getDiscount(),
                    o.getPromotionSummary(), !o.isPromotionCleared());
        }

        public static OrderDto from(Order o) {
            return from(o, null, false);
        }
    }
}
