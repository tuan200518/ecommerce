package com.shop.audit.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AuditEntryRepository extends JpaRepository<AuditEntry, Long>, JpaSpecificationExecutor<AuditEntry> {

    boolean existsByEventId(String eventId);

    long countByOccurredAtGreaterThanEqual(Instant since);

    long countByOutcomeAndOccurredAtGreaterThanEqual(String outcome, Instant since);

    long countByActionAndOccurredAtGreaterThanEqual(String action, Instant since);

    @Query("""
            SELECT COUNT(DISTINCT a.actorEmail) FROM AuditEntry a
            WHERE a.clientIp = :ip AND a.action IN ('LOGIN_FAILED', 'ACCOUNT_LOCKED') AND a.occurredAt >= :since
            """)
    long countDistinctFailedEmailsFromIp(@Param("ip") String ip, @Param("since") Instant since);

    @Query("""
            SELECT a.action, COUNT(a) FROM AuditEntry a WHERE a.occurredAt >= :since
            GROUP BY a.action ORDER BY COUNT(a) DESC
            """)
    List<Object[]> countByAction(@Param("since") Instant since);

    @Query("""
            SELECT a.source, COUNT(a) FROM AuditEntry a WHERE a.occurredAt >= :since
            GROUP BY a.source ORDER BY COUNT(a) DESC
            """)
    List<Object[]> countBySource(@Param("since") Instant since);

    @Query("""
            SELECT a.clientIp, COUNT(a), COUNT(DISTINCT a.actorEmail) FROM AuditEntry a
            WHERE a.outcome = 'FAILURE' AND a.clientIp IS NOT NULL AND a.occurredAt >= :since
            GROUP BY a.clientIp ORDER BY COUNT(a) DESC
            """)
    List<Object[]> failuresByIp(@Param("since") Instant since);

    @Query("SELECT DISTINCT a.action FROM AuditEntry a ORDER BY a.action")
    List<String> distinctActions();
}
