package com.shop.promotion.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface FlashSaleRepository extends JpaRepository<FlashSale, Long> {

    Page<FlashSale> findAllByOrderByStartsAtDesc(Pageable pageable);

    /** Chiến dịch chưa hủy, chưa kết thúc và bắt đầu trước {@code until} (đang diễn ra + sắp diễn ra). */
    @Query("""
            SELECT f FROM FlashSale f WHERE f.cancelled = false AND f.endsAt > :now AND f.startsAt < :until
            ORDER BY f.startsAt
            """)
    List<FlashSale> findCurrentAndUpcoming(@Param("now") Instant now, @Param("until") Instant until);

    /** Sản phẩm đang thuộc một chiến dịch khác có khung giờ chồng lấn → không cho tạo trùng. */
    @Query("""
            SELECT DISTINCT i.productId FROM FlashSaleItem i JOIN i.flashSale f
            WHERE f.cancelled = false AND i.productId IN :productIds AND f.startsAt < :endsAt AND f.endsAt > :startsAt
            """)
    List<Long> findOverlappingProducts(@Param("productIds") Collection<Long> productIds,
                                       @Param("startsAt") Instant startsAt, @Param("endsAt") Instant endsAt);
}
