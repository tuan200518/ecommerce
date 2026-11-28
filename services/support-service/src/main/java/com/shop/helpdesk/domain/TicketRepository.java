package com.shop.helpdesk.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket> {

    Page<Ticket> findByUserIdOrderByUpdatedAtDesc(long userId, Pageable pageable);

    long countByUserIdAndStatusIn(long userId, Collection<Ticket.Status> statuses);

    List<Ticket> findByOrderIdAndStatusIn(long orderId, Collection<Ticket.Status> statuses);

    long countByStatus(Ticket.Status status);

    long countByStatusInAndAssigneeIdIsNull(Collection<Ticket.Status> statuses);

    @Query("""
            SELECT COUNT(t) FROM Ticket t WHERE t.status IN :active AND t.firstResponseAt IS NULL
            AND t.firstResponseDueAt < :now
            """)
    long countOverdue(@Param("active") Collection<Ticket.Status> active, @Param("now") Instant now);

    @Query("""
            SELECT t FROM Ticket t WHERE t.status IN :active AND t.firstResponseAt IS NULL
            AND t.firstResponseDueAt < :now AND t.slaBreachNotified = false
            """)
    List<Ticket> findNewlyOverdue(@Param("active") Collection<Ticket.Status> active, @Param("now") Instant now);

    List<Ticket> findTop100ByStatusAndResolvedAtBefore(Ticket.Status status, Instant before);

    long countByStatusInAndResolvedAtGreaterThanEqual(Collection<Ticket.Status> statuses, Instant since);

    @Query("SELECT t FROM Ticket t WHERE t.firstResponseAt IS NOT NULL AND t.createdAt >= :since")
    List<Ticket> findResponded(@Param("since") Instant since);

    @Query("SELECT AVG(t.rating), COUNT(t.rating) FROM Ticket t WHERE t.rating IS NOT NULL AND t.createdAt >= :since")
    List<Object[]> ratingStats(@Param("since") Instant since);
}
