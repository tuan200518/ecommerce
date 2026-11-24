package com.shop.promotion.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface FlashSaleItemRepository extends JpaRepository<FlashSaleItem, Long> {

    /** Sản phẩm đang có giá flash sale tại thời điểm {@code at}. */
    @Query("""
            SELECT i FROM FlashSaleItem i JOIN FETCH i.flashSale f
            WHERE i.productId IN :productIds AND f.cancelled = false AND f.startsAt <= :at AND f.endsAt > :at
            """)
    List<FlashSaleItem> findActive(@Param("productIds") Collection<Long> productIds, @Param("at") Instant at);
}
