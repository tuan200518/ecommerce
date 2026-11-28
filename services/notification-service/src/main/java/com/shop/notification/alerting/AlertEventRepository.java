package com.shop.notification.alerting;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {

    Page<AlertEvent> findAllByOrderByReceivedAtDesc(Pageable pageable);
}
