package com.shop.fraud.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface PaymentFailureRepository extends JpaRepository<PaymentFailure, Long> {

    long countByUserIdAndOccurredAtGreaterThanEqual(long userId, Instant since);
}
