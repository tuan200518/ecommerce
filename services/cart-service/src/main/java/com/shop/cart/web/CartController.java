package com.shop.cart.web;

import com.shop.cart.service.CartService;
import com.shop.cart.service.CartService.CartView;
import com.shop.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService service;

    public CartController(CartService service) {
        this.service = service;
    }

    public record AddItemRequest(@NotNull Long productId, @Min(1) @Max(20) int quantity) {
    }

    public record UpdateItemRequest(@Min(0) @Max(20) int quantity) {
    }

    @GetMapping
    public CartView get(AuthenticatedUser user) {
        return service.view(user.userId());
    }

    @PostMapping("/items")
    public CartView add(AuthenticatedUser user, @Valid @RequestBody AddItemRequest req) {
        return service.add(user.userId(), req.productId(), req.quantity());
    }

    @PutMapping("/items/{productId}")
    public CartView update(AuthenticatedUser user, @PathVariable long productId,
                           @Valid @RequestBody UpdateItemRequest req) {
        return service.update(user.userId(), productId, req.quantity());
    }

    @DeleteMapping("/items/{productId}")
    public CartView remove(AuthenticatedUser user, @PathVariable long productId) {
        return service.remove(user.userId(), productId);
    }

    @DeleteMapping
    public CartView clear(AuthenticatedUser user) {
        return service.clear(user.userId());
    }
}
