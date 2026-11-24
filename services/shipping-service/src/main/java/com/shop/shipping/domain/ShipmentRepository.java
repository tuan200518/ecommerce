package com.shop.shipping.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    Optional<Shipment> findByOrderId(long orderId);

    Page<Shipment> findByStatusOrderByUpdatedAtAsc(ShipmentStatus status, Pageable pageable);

    Page<Shipment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("select s.status, count(s) from Shipment s group by s.status")
    List<Object[]> countByStatus();

    @Query("select s.carrier, count(s) from Shipment s group by s.carrier")
    List<Object[]> countByCarrier();
}
