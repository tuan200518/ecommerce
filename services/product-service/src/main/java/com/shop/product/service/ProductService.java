package com.shop.product.service;

import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.ProductEvents.ProductChanged;
import com.shop.product.domain.Category;
import com.shop.product.domain.CategoryRepository;
import com.shop.product.domain.Product;
import com.shop.product.domain.ProductRepository;
import com.shop.product.domain.ProductSpecs;
import com.shop.product.web.ProductDtos.CategoryDto;
import com.shop.product.web.ProductDtos.CategoryRequest;
import com.shop.product.web.ProductDtos.ProductDto;
import com.shop.product.web.ProductDtos.ProductRequest;
import com.shop.support.outbox.OutboxService;
import com.shop.support.web.ApiException;
import com.shop.support.web.PageResponse;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ProductService {

    public static final String PRODUCT_CACHE = "product";
    public static final String CATEGORY_CACHE = "categories";

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final OutboxService outbox;

    public ProductService(ProductRepository products, CategoryRepository categories, OutboxService outbox) {
        this.products = products;
        this.categories = categories;
        this.outbox = outbox;
    }

    // ------------------------------------------------------------------ Truy vấn

    @Transactional(readOnly = true)
    public PageResponse<ProductDto> search(String q, Long categoryId, BigDecimal minPrice, BigDecimal maxPrice,
                                           String sort, boolean includeInactive, int page, int size) {
        Sort order = switch (sort == null ? "" : sort) {
            case "price_asc" -> Sort.by("price").ascending();
            case "price_desc" -> Sort.by("price").descending();
            case "name" -> Sort.by("name").ascending();
            default -> Sort.by("id").descending();
        };
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), order);
        return PageResponse.of(products.findAll(
                ProductSpecs.search(q, categoryId, minPrice, maxPrice, includeInactive), pr), ProductDto::from);
    }

    @Cacheable(cacheNames = PRODUCT_CACHE, key = "#id")
    @Transactional(readOnly = true)
    public ProductDto get(long id) {
        return products.findById(id).map(ProductDto::from)
                .orElseThrow(() -> ApiException.notFound("Không tìm thấy sản phẩm"));
    }

    @Cacheable(cacheNames = CATEGORY_CACHE, key = "'all'")
    @Transactional(readOnly = true)
    public List<CategoryDto> categories() {
        return categories.findAllByOrderByNameAsc().stream().map(CategoryDto::from).toList();
    }

    // ------------------------------------------------------------------ Quản lý sản phẩm

    @Transactional
    public ProductDto create(ProductRequest req) {
        if (products.existsBySku(req.sku())) {
            throw ApiException.conflict("SKU " + req.sku() + " đã tồn tại");
        }
        Product p = new Product(req.sku(), req.name(), req.description(), req.price(), category(req.categoryId()),
                req.imageUrl());
        p.replaceImages(req.images());
        products.save(p);
        publish(EventTypes.PRODUCT_CREATED, p, req.initialStock());
        return ProductDto.from(p);
    }

    @Caching(evict = @CacheEvict(cacheNames = PRODUCT_CACHE, key = "#id"))
    @Transactional
    public ProductDto update(long id, ProductRequest req) {
        Product p = load(id);
        p.update(req.name(), req.description(), req.price(), category(req.categoryId()), req.imageUrl());
        if (req.images() != null) {
            p.replaceImages(req.images());
        }
        publish(EventTypes.PRODUCT_UPDATED, p, null);
        return ProductDto.from(p);
    }

    @CacheEvict(cacheNames = PRODUCT_CACHE, key = "#id")
    @Transactional
    public ProductDto setActive(long id, boolean active) {
        Product p = load(id);
        p.setActive(active);
        publish(active ? EventTypes.PRODUCT_UPDATED : EventTypes.PRODUCT_DISCONTINUED, p, null);
        return ProductDto.from(p);
    }

    // ------------------------------------------------------------------ Danh mục

    @CacheEvict(cacheNames = CATEGORY_CACHE, allEntries = true)
    @Transactional
    public CategoryDto createCategory(CategoryRequest req) {
        if (categories.existsBySlug(req.slug())) {
            throw ApiException.conflict("Slug đã tồn tại");
        }
        return CategoryDto.from(categories.save(new Category(req.name(), req.slug(), req.description())));
    }

    @CacheEvict(cacheNames = CATEGORY_CACHE, allEntries = true)
    @Transactional
    public CategoryDto updateCategory(long id, CategoryRequest req) {
        Category c = categories.findById(id).orElseThrow(() -> ApiException.notFound("Không tìm thấy danh mục"));
        c.update(req.name(), req.slug(), req.description());
        return CategoryDto.from(c);
    }

    @CacheEvict(cacheNames = CATEGORY_CACHE, allEntries = true)
    @Transactional
    public void deleteCategory(long id) {
        if (products.countByCategoryId(id) > 0) {
            throw ApiException.conflict("Danh mục đang có sản phẩm, không thể xóa");
        }
        categories.deleteById(id);
    }

    // ------------------------------------------------------------------ Nội bộ

    private void publish(String type, Product p, Integer initialStock) {
        Category c = p.getCategory();
        outbox.publish(Topics.PRODUCT_EVENTS, type, String.valueOf(p.getId()),
                new ProductChanged(p.getId(), p.getSku(), p.getName(), p.getPrice(),
                        c == null ? null : c.getId(), c == null ? null : c.getName(), p.getImageUrl(),
                        p.isActive(), initialStock));
    }

    private Category category(Long id) {
        if (id == null) {
            return null;
        }
        return categories.findById(id).orElseThrow(() -> ApiException.badRequest("Danh mục không tồn tại"));
    }

    private Product load(long id) {
        return products.findById(id).orElseThrow(() -> ApiException.notFound("Không tìm thấy sản phẩm"));
    }
}
