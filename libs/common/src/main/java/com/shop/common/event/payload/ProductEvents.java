package com.shop.common.event.payload;

import java.math.BigDecimal;

/** Payload cho topic product-events (ProductCreated / ProductUpdated / ProductDiscontinued). */
public final class ProductEvents {

    /**
     * Ảnh chụp trạng thái sản phẩm. Các service khác (cart, order, inventory) dùng sự kiện này
     * để duy trì read-model cục bộ thay vì gọi đồng bộ sang Product Service.
     *
     * @param initialStock chỉ có ý nghĩa với ProductCreated: tồn kho ban đầu cho Inventory Service.
     */
    public record ProductChanged(long productId, String sku, String name, BigDecimal price,
                                 Long categoryId, String categoryName, String imageUrl,
                                 boolean active, Integer initialStock) {
    }

    private ProductEvents() {
    }
}
