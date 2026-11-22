package com.shop.product.web;

import com.shop.product.domain.Category;
import com.shop.product.domain.Product;
import com.shop.product.domain.ProductImage;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ProductDtos {

    private ProductDtos() {
    }

    public record ProductDto(long id, String sku, String name, String description, BigDecimal price,
                             Long categoryId, String categoryName, String imageUrl, List<String> images,
                             boolean active, Instant createdAt, Instant updatedAt) {
        public static ProductDto from(Product p) {
            Category c = p.getCategory();
            return new ProductDto(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getPrice(),
                    c == null ? null : c.getId(), c == null ? null : c.getName(), p.getImageUrl(),
                    p.getImages().stream().map(ProductImage::getUrl).toList(),
                    p.isActive(), p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record CategoryDto(long id, String name, String slug, String description) {
        public static CategoryDto from(Category c) {
            return new CategoryDto(c.getId(), c.getName(), c.getSlug(), c.getDescription());
        }
    }

    public record ProductRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+", message = "SKU chỉ gồm chữ, số, - và _") String sku,
            @NotBlank @Size(max = 255) String name,
            @Size(max = 4000) String description,
            @NotNull @DecimalMin(value = "0.0", inclusive = false, message = "Giá phải lớn hơn 0") BigDecimal price,
            Long categoryId,
            @Size(max = 1000) String imageUrl,
            @Size(max = 10) List<@Size(max = 1000) String> images,
            @Min(0) @Max(1_000_000) Integer initialStock) {
    }

    public record CategoryRequest(@NotBlank @Size(max = 100) String name,
                                  @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z0-9-]+") String slug,
                                  @Size(max = 500) String description) {
    }
}
