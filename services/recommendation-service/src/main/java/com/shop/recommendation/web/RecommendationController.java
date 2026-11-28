package com.shop.recommendation.web;

import com.shop.common.security.AuthenticatedUser;
import com.shop.recommendation.service.RecommendationService;
import com.shop.recommendation.web.RecommendationDtos.AdminStats;
import com.shop.recommendation.web.RecommendationDtos.ClickRequest;
import com.shop.recommendation.web.RecommendationDtos.RecommendationList;
import com.shop.recommendation.web.RecommendationDtos.UserProfile;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Các API gợi ý là công khai (Gateway); khách đã đăng nhập nhận gợi ý cá nhân hóa.
 * /api/recommendations/admin/** dành cho ADMIN / ANALYST / PRODUCT_MANAGER.
 */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    @GetMapping("/products/{productId}/similar")
    public RecommendationList similar(@PathVariable long productId, @RequestParam(defaultValue = "8") int limit) {
        return service.similar(productId, limit);
    }

    @GetMapping("/products/{productId}/bought-together")
    public RecommendationList boughtTogether(@PathVariable long productId, @RequestParam(defaultValue = "4") int limit) {
        return service.boughtTogether(productId, limit);
    }

    @GetMapping("/for-you")
    public RecommendationList forYou(Optional<AuthenticatedUser> user, @RequestParam(defaultValue = "12") int limit) {
        return service.forYou(user, limit);
    }

    @GetMapping("/trending")
    public RecommendationList trending(@RequestParam(defaultValue = "12") int limit,
                                       @RequestParam(required = false) Long categoryId) {
        return service.trending(limit, categoryId);
    }

    /** @param productIds các sản phẩm đang có trong giỏ */
    @GetMapping("/cart")
    public RecommendationList cart(@RequestParam(required = false) List<Long> productIds,
                                   @RequestParam(defaultValue = "6") int limit) {
        return service.cart(productIds == null ? List.of()
                : productIds.stream().filter(Objects::nonNull).distinct().toList(), limit);
    }

    /** Khách bấm vào một sản phẩm được gợi ý (đo CTR và quy đơn hàng về gợi ý). */
    @PostMapping("/clicks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void click(Optional<AuthenticatedUser> user, @Valid @RequestBody ClickRequest req) {
        service.click(user, req.placement(), req.productId());
    }

    @GetMapping("/admin/stats")
    public AdminStats stats(AuthenticatedUser user, @RequestParam(defaultValue = "7") int days) {
        return service.stats(user, days);
    }

    @GetMapping("/admin/users/{userId}")
    public UserProfile explain(AuthenticatedUser user, @PathVariable long userId,
                               @RequestParam(defaultValue = "12") int limit) {
        return service.explain(user, userId, limit);
    }
}
