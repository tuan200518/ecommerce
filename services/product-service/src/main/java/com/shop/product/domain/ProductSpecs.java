package com.shop.product.domain;

import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;

/** Điều kiện tìm kiếm/lọc sản phẩm (JPA Criteria). */
public final class ProductSpecs {

    private ProductSpecs() {
    }

    public static Specification<Product> search(String q, Long categoryId, BigDecimal minPrice, BigDecimal maxPrice,
                                                boolean includeInactive) {
        Specification<Product> spec = (root, query, cb) -> cb.conjunction();
        if (!includeInactive) {
            spec = spec.and((root, query, cb) -> cb.isTrue(root.get("active")));
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), like),
                    cb.like(cb.lower(root.get("sku")), like),
                    cb.like(cb.lower(root.get("description")), like)));
        }
        if (categoryId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId));
        }
        if (minPrice != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("price"), minPrice));
        }
        if (maxPrice != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("price"), maxPrice));
        }
        return spec;
    }
}
