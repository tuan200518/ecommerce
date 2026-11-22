package com.shop.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByOrderIdOrderByProductIdAsc(long orderId);
}
