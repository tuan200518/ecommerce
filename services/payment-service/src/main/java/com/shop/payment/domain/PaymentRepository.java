package com.shop.payment.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(long orderId);

    Page<Payment> findAllByOrderByIdDesc(Pageable pageable);

    Page<Payment> findByStatusOrderByIdDesc(Payment.Status status, Pageable pageable);

    @Query("select p.status as status, count(p) as count, coalesce(sum(p.amount), 0) as amount from Payment p group by p.status")
    List<StatusSummary> summarize();

    interface StatusSummary {
        Payment.Status getStatus();

        long getCount();

        BigDecimal getAmount();
    }
}
