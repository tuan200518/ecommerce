package com.shop.notification.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("select n from Notification n where n.userId = :userId or n.targetRole in :roles order by n.id desc")
    Page<Notification> findVisible(@Param("userId") long userId, @Param("roles") Collection<String> roles,
                                   Pageable pageable);

    long countByUserIdAndReadFalse(long userId);

    @Modifying
    @Query("update Notification n set n.read = true where n.userId = :userId and n.read = false")
    int markAllRead(@Param("userId") long userId);
}
