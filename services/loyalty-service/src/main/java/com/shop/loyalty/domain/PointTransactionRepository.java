package com.shop.loyalty.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface PointTransactionRepository extends JpaRepository<PointTransaction, Long> {

    boolean existsByUserIdAndTypeAndReferenceId(long userId, PointTransaction.Type type, String referenceId);

    List<PointTransaction> findByReferenceIdAndReversedFalseAndPointsGreaterThan(String referenceId, long points);

    Page<PointTransaction> findByUserIdOrderByIdDesc(long userId, Pageable pageable);

    long countByUserIdAndTypeAndCreatedAtGreaterThanEqual(long userId, PointTransaction.Type type, Instant since);

    @Query("select coalesce(sum(t.spendAmount), 0) from PointTransaction t where t.userId = :userId and t.createdAt >= :since")
    BigDecimal spendSince(long userId, Instant since);
}
