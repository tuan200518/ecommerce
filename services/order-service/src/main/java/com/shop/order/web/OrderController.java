package com.shop.order.web;

import com.shop.common.security.AuthenticatedUser;
import com.shop.order.service.OrderService;
import com.shop.order.web.OrderDtos.CreateOrderRequest;
import com.shop.order.web.OrderDtos.OrderDto;
import com.shop.order.web.OrderDtos.UpdateStatusRequest;
import com.shop.support.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    /** Đặt hàng: trả 202 vì kết quả cuối (giữ hàng/thanh toán) được xử lý bất đồng bộ qua Kafka. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OrderDto create(AuthenticatedUser user, @Valid @RequestBody CreateOrderRequest req) {
        return service.createOrder(user, req);
    }

    @GetMapping("/my")
    public PageResponse<OrderDto> myOrders(AuthenticatedUser user,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        return service.listMine(user, page, size);
    }

    @GetMapping("/{id}")
    public OrderDto get(AuthenticatedUser user, @PathVariable long id) {
        return service.getOrder(user, id);
    }

    @PostMapping("/{id}/cancel")
    public OrderDto cancel(AuthenticatedUser user, @PathVariable long id) {
        return service.cancelByCustomer(user, id);
    }

    // ------------------------------------------------ Quản trị

    @GetMapping
    public PageResponse<OrderDto> all(AuthenticatedUser user,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return service.listAll(user, status, page, size);
    }

    @PutMapping("/{id}/status")
    public OrderDto updateStatus(AuthenticatedUser user, @PathVariable long id,
                                 @Valid @RequestBody UpdateStatusRequest req) {
        return service.updateStatusByStaff(user, id, req);
    }
}
