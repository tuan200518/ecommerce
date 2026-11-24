package com.shop.review.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PurchaseRepository extends JpaRepository<Purchase, Long> {

    List<Purchase> findByOrderId(long orderId);

    boolean existsByOrderId(long orderId);

    List<Purchase> findByUserIdAndProductIdAndDeliveredTrue(long userId, long productId);

    boolean existsByUserIdAndProductId(long userId, long productId);

    @Modifying
    @Query("delete from Purchase p where p.orderId = :orderId and p.delivered = false")
    int deleteUndelivered(long orderId);
}
