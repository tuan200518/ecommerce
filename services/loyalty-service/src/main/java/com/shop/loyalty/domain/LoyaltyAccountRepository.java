package com.shop.loyalty.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount, Long> {

    Optional<LoyaltyAccount> findByReferralCode(String referralCode);

    long countByReferredBy(long referrerId);

    Page<LoyaltyAccount> findAllByOrderByBalanceDesc(Pageable pageable);

    /** Khóa dòng tài khoản khi đổi điểm → hai yêu cầu đổi đồng thời không thể cùng tiêu một số dư. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM LoyaltyAccount a WHERE a.userId = :userId")
    Optional<LoyaltyAccount> findForUpdate(@Param("userId") long userId);
}
