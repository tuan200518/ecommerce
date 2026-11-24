package com.shop.promotion.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface RedemptionRepository extends JpaRepository<Redemption, Long> {

    List<Redemption> findByOrderIdAndStatus(long orderId, Redemption.Status status);

    boolean existsByOrderId(long orderId);

    @Query("""
            SELECT r.kind, COUNT(DISTINCT r.orderId), COALESCE(SUM(r.discount), 0) FROM Redemption r
            WHERE r.status = 'RESERVED' AND r.createdAt >= :since GROUP BY r.kind
            """)
    List<Object[]> totalsByKind(@Param("since") Instant since);

    @Query("""
            SELECT COUNT(DISTINCT r.orderId) FROM Redemption r WHERE r.status = 'RESERVED' AND r.createdAt >= :since
            """)
    long countOrders(@Param("since") Instant since);

    @Query("""
            SELECT r.label, COUNT(r), COALESCE(SUM(r.discount), 0) FROM Redemption r
            WHERE r.kind = 'VOUCHER' AND r.status = 'RESERVED' AND r.createdAt >= :since
            GROUP BY r.label ORDER BY COUNT(r) DESC
            """)
    List<Object[]> topVouchers(@Param("since") Instant since);
}
