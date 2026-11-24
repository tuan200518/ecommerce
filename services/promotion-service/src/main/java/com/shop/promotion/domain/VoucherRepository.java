package com.shop.promotion.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VoucherRepository extends JpaRepository<Voucher, Long>, JpaSpecificationExecutor<Voucher> {

    Optional<Voucher> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsBySourceRef(String sourceRef);

    List<Voucher> findTop50ByOwnerUserIdOrderByCreatedAtDesc(long ownerUserId);

    @Query("""
            SELECT v FROM Voucher v WHERE v.ownerUserId IS NULL AND v.active = true
            AND v.startsAt <= :now AND v.endsAt > :now AND (v.usageLimit IS NULL OR v.used < v.usageLimit)
            ORDER BY v.endsAt
            """)
    List<Voucher> findPublicAvailable(@Param("now") Instant now);
}
