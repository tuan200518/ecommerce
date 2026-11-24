package com.shop.fraud.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

public interface OrderRecordRepository extends JpaRepository<OrderRecord, Long> {

    @Query("select count(o) from OrderRecord o where o.userId = :userId and o.createdAt >= :since and o.orderId <> :excludeOrderId")
    long countRecentOrders(long userId, Instant since, long excludeOrderId);

    @Query("select count(o) from OrderRecord o where o.userId = :userId and o.cancelled = true and o.createdAt >= :since")
    long countRecentCancellations(long userId, Instant since);

    @Query("select count(distinct o.userId) from OrderRecord o "
            + "where o.addressKey = :addressKey and o.userId <> :userId and o.createdAt >= :since")
    long countOtherUsersWithAddress(String addressKey, long userId, Instant since);
}
