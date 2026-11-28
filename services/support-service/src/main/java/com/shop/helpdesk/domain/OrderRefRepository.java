package com.shop.helpdesk.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRefRepository extends JpaRepository<OrderRef, Long> {

    List<OrderRef> findTop20ByUserIdOrderByOrderIdDesc(long userId);
}
