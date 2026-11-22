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
            String paymentMethod) {
    }

    public record ItemRequest(@NotNull Long productId, @Min(1) @Max(1000) int quantity) {
    }

    public record UpdateStatusRequest(@NotBlank String status, @Size(max = 500) String note) {
    }

    public record OrderItemDto(long productId, String productName, BigDecimal unitPrice, int quantity,
                               BigDecimal lineTotal) {
        static OrderItemDto from(OrderItem i) {
            return new OrderItemDto(i.getProductId(), i.getProductName(), i.getUnitPrice(), i.getQuantity(),
                    i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())));
        }
    }

    public record HistoryDto(String fromStatus, String toStatus, String note, Instant changedAt) {
        static HistoryDto from(OrderStatusHistory h) {
            return new HistoryDto(h.getFromStatus() == null ? null : h.getFromStatus().name(),
                    h.getToStatus().name(), h.getNote(), h.getChangedAt());
        }
    }

    public record OrderDto(long id, long userId, String status, BigDecimal totalAmount, String paymentMethod,
                           String shippingAddress, String cancelReason, Instant createdAt, Instant updatedAt,
                           List<OrderItemDto> items, List<HistoryDto> history) {

        public static OrderDto from(Order o, List<OrderStatusHistory> history) {
            return new OrderDto(o.getId(), o.getUserId(), o.getStatus().name(), o.getTotalAmount(),
                    o.getPaymentMethod(), o.getShippingAddress(), o.getCancelReason(),
                    o.getCreatedAt(), o.getUpdatedAt(),
                    o.getItems().stream().map(OrderItemDto::from).toList(),
                    history == null ? null : history.stream().map(HistoryDto::from).toList());
        }

        public static OrderDto from(Order o) {
            return from(o, null);
        }
    }
}
