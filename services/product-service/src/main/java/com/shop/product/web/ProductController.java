package com.shop.product.web;

import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.product.service.ActivityTracker;
import com.shop.product.service.ProductService;
import com.shop.product.web.ProductDtos.CategoryDto;
import com.shop.product.web.ProductDtos.CategoryRequest;
import com.shop.product.web.ProductDtos.ProductDto;
import com.shop.product.web.ProductDtos.ProductRequest;
import com.shop.support.web.Access;
import com.shop.support.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@RestController
public class ProductController {

    private final ProductService service;
    private final ActivityTracker tracker;

    public ProductController(ProductService service, ActivityTracker tracker) {
        this.service = service;
        this.tracker = tracker;
    }

    // --------------------------------------------- Công khai

    @GetMapping("/api/products")
    public PageResponse<ProductDto> search(Optional<AuthenticatedUser> user,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) Long categoryId,
                                           @RequestParam(required = false) BigDecimal minPrice,
                                           @RequestParam(required = false) BigDecimal maxPrice,
                                           @RequestParam(required = false) String sort,
                                           @RequestParam(defaultValue = "false") boolean includeInactive,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "12") int size) {
        boolean staff = user.map(u -> u.hasAnyRole(Roles.ADMIN, Roles.PRODUCT_MANAGER)).orElse(false);
        PageResponse<ProductDto> result = service.search(q, categoryId, minPrice, maxPrice, sort,
                includeInactive && staff, page, size);
        if (q != null && !q.isBlank() && page == 0 && !staff) {
            track(() -> tracker.searchPerformed(user.map(AuthenticatedUser::userId).orElse(null), q, categoryId,
                    result.totalElements()));
        }
        return result;
    }

    @GetMapping("/api/products/{id}")
    public ProductDto get(Optional<AuthenticatedUser> user, @PathVariable long id) {
        ProductDto product = service.get(id);
        track(() -> tracker.productViewed(user.map(AuthenticatedUser::userId).orElse(null), product));
        return product;
    }

    /** Ghi nhận hành vi là best-effort: lỗi (vd. DB bận) không được làm hỏng request của khách. */
    private static void track(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(ProductController.class).debug("Bỏ qua lỗi ghi nhận hành vi: {}", e.getMessage());
        }
    }

    @GetMapping("/api/categories")
    public List<CategoryDto> categories() {
        return service.categories();
    }

    // --------------------------------------------- Quản trị (PRODUCT_MANAGER / ADMIN)

    @PostMapping("/api/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductDto create(AuthenticatedUser user, @Valid @RequestBody ProductRequest req) {
        requireManager(user);
        return service.create(req, user);
    }

    @PutMapping("/api/products/{id}")
    public ProductDto update(AuthenticatedUser user, @PathVariable long id, @Valid @RequestBody ProductRequest req) {
        requireManager(user);
        return service.update(id, req, user);
    }

    @DeleteMapping("/api/products/{id}")
    public ProductDto discontinue(AuthenticatedUser user, @PathVariable long id) {
        requireManager(user);
        return service.setActive(id, false, user);
    }

    @PostMapping("/api/products/{id}/activate")
    public ProductDto activate(AuthenticatedUser user, @PathVariable long id) {
        requireManager(user);
        return service.setActive(id, true, user);
    }

    @PostMapping("/api/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryDto createCategory(AuthenticatedUser user, @Valid @RequestBody CategoryRequest req) {
        requireManager(user);
        return service.createCategory(req, user);
    }

    @PutMapping("/api/categories/{id}")
    public CategoryDto updateCategory(AuthenticatedUser user, @PathVariable long id,
                                      @Valid @RequestBody CategoryRequest req) {
        requireManager(user);
        return service.updateCategory(id, req, user);
    }

    @DeleteMapping("/api/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(AuthenticatedUser user, @PathVariable long id) {
        requireManager(user);
        service.deleteCategory(id, user);
    }

    private static void requireManager(AuthenticatedUser user) {
        Access.requireAnyRole(user, Roles.ADMIN, Roles.PRODUCT_MANAGER);
    }
}
