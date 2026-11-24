package com.shop.shipping.web;

import com.shop.common.security.AuthenticatedUser;
import com.shop.shipping.service.ShippingService;
import com.shop.shipping.web.ShippingDtos.CarrierDto;
import com.shop.shipping.web.ShippingDtos.CarrierRequest;
import com.shop.shipping.web.ShippingDtos.ShipmentDto;
import com.shop.shipping.web.ShippingDtos.StatusRequest;
import com.shop.shipping.web.ShippingDtos.SummaryDto;
import com.shop.support.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/shipments")
public class ShippingController {

    private final ShippingService service;

    public ShippingController(ShippingService service) {
        this.service = service;
    }

    /** Vận đơn + timeline của một đơn hàng (chủ đơn hoặc nhân viên). */
    @GetMapping("/orders/{orderId}")
    public ShipmentDto byOrder(AuthenticatedUser user, @PathVariable long orderId) {
        return service.byOrder(user, orderId);
    }

    @GetMapping("/carriers")
    public List<CarrierDto> carriers() {
        return service.carriers();
    }

    // ------------------------------------------------ Nhân viên

    @GetMapping
    public PageResponse<ShipmentDto> list(AuthenticatedUser user,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return service.list(user, status, page, size);
    }

    @GetMapping("/summary")
    public SummaryDto summary(AuthenticatedUser user) {
        return service.summary(user);
    }

    @GetMapping("/{id}")
    public ShipmentDto get(AuthenticatedUser user, @PathVariable long id) {
        return service.get(user, id);
    }

    @PutMapping("/{id}/status")
    public ShipmentDto updateStatus(AuthenticatedUser user, @PathVariable long id,
                                    @Valid @RequestBody StatusRequest req) {
        return service.updateStatus(user, id, req.status(), req.note());
    }

    @PutMapping("/{id}/carrier")
    public ShipmentDto changeCarrier(AuthenticatedUser user, @PathVariable long id,
                                     @Valid @RequestBody CarrierRequest req) {
        return service.changeCarrier(user, id, req.carrier());
    }
}
