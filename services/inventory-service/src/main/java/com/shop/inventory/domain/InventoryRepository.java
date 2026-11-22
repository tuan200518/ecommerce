package com.shop.inventory.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /**
     * Giữ hàng nguyên tử: chỉ trừ khi còn đủ. Database đảm bảo hai transaction đồng thời
     * không thể cùng "thấy" số lượng cũ → không bao giờ bán vượt tồn kho (kịch bản Flash Sale).
     *
     * @return 1 nếu giữ thành công, 0 nếu không đủ hàng.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Inventory i set i.available = i.available - :qty, i.reserved = i.reserved + :qty, i.updatedAt = :now
            where i.productId = :productId and i.available >= :qty""")
    int tryReserve(@Param("productId") long productId, @Param("qty") int qty, @Param("now") Instant now);

    /** Đơn đã thanh toán: hàng đang giữ được xuất kho. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Inventory i set i.reserved = i.reserved - :qty, i.updatedAt = :now
            where i.productId = :productId and i.reserved >= :qty""")
    int commitReserved(@Param("productId") long productId, @Param("qty") int qty, @Param("now") Instant now);

    /** Hoàn hàng đang giữ về kho khả dụng (hành động bù của Saga). */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Inventory i set i.available = i.available + :qty, i.reserved = i.reserved - :qty, i.updatedAt = :now
            where i.productId = :productId and i.reserved >= :qty""")
    int releaseReserved(@Param("productId") long productId, @Param("qty") int qty, @Param("now") Instant now);

    /** Nhập thêm hàng hoặc nhận lại hàng của đơn đã xuất kho nhưng bị hủy. */
    @Modifying(flushAutomatically = true)
    @Query("update Inventory i set i.available = i.available + :qty, i.updatedAt = :now where i.productId = :productId")
    int addAvailable(@Param("productId") long productId, @Param("qty") int qty, @Param("now") Instant now);

    @Query("select i from Inventory i where i.available <= i.lowStockThreshold order by i.available asc")
    Page<Inventory> findLowStock(Pageable pageable);

    Page<Inventory> findAllByOrderByProductIdAsc(Pageable pageable);
}
