package com.shop.fraud.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RiskCaseRepository extends JpaRepository<RiskCase, Long> {

    Page<RiskCase> findByStatusOrderByCreatedAtDesc(RiskCase.Status status, Pageable pageable);

    Page<RiskCase> findByLevelOrderByCreatedAtDesc(String level, Pageable pageable);

    Page<RiskCase> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("select c.status, count(c) from RiskCase c group by c.status")
    List<Object[]> countByStatus();

    @Query("select c.level, count(c) from RiskCase c group by c.level")
    List<Object[]> countByLevel();
}
