package com.shop.product.web;

import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.product.service.ProductService;
import com.shop.product.web.ProductDtos.CategoryDto;
import com.shop.product.web.ProductDtos.CategoryRequest;
import com.shop.product.web.ProductDtos.ProductDto;
import com.shop.product.web.ProductDtos.ProductRequest;
import com.shop.support.web.Access;
import com.shop.support.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

    public ProductController(ProductService service) {
        this.service = service;
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
        return service.search(q, categoryId, minPrice, maxPrice, sort, includeInactive && staff, page, size);
    }

    @GetMapping("/api/products/{id}")
    public ProductDto get(@PathVariable long id) {
        return service.get(id);
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
        return service.create(req);
    }

    @PutMapping("/api/products/{id}")
    public ProductDto update(AuthenticatedUser user, @PathVariable long id, @Valid @RequestBody ProductRequest req) {
        requireManager(user);
        return service.update(id, req);
    }

    @DeleteMapping("/api/products/{id}")
    public ProductDto discontinue(AuthenticatedUser user, @PathVariable long id) {
        requireManager(user);
        return service.setActive(id, false);
    }

    @PostMapping("/api/products/{id}/activate")
    public ProductDto activate(AuthenticatedUser user, @PathVariable long id) {
        requireManager(user);
        return service.setActive(id, true);
    }

    @PostMapping("/api/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryDto createCategory(AuthenticatedUser user, @Valid @RequestBody CategoryRequest req) {
        requireManager(user);
        return service.createCategory(req);
    }

    @PutMapping("/api/categories/{id}")
    public CategoryDto updateCategory(AuthenticatedUser user, @PathVariable long id,
                                      @Valid @RequestBody CategoryRequest req) {
        requireManager(user);
        return service.updateCategory(id, req);
    }

    @DeleteMapping("/api/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(AuthenticatedUser user, @PathVariable long id) {
        requireManager(user);
        service.deleteCategory(id);
    }

    private static void requireManager(AuthenticatedUser user) {
        Access.requireAnyRole(user, Roles.ADMIN, Roles.PRODUCT_MANAGER);
    }
}
