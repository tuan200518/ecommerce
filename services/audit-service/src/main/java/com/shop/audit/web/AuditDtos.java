package com.shop.audit.web;

import com.shop.audit.domain.AuditEntry;

import java.time.Instant;
import java.util.List;

public final class AuditDtos {

    private AuditDtos() {
    }

    public record AuditDto(long id, Instant occurredAt, String source, String correlationId, Long actorId,
                           String actorEmail, String actorRoles, String action, String resourceType,
                           String resourceId, String outcome, String detail, String clientIp) {
        public static AuditDto from(AuditEntry e) {
            return new AuditDto(e.getId(), e.getOccurredAt(), e.getSource(), e.getCorrelationId(), e.getActorId(),
                    e.getActorEmail(), e.getActorRoles(), e.getAction(), e.getResourceType(), e.getResourceId(),
                    e.getOutcome(), e.getDetail(), e.getClientIp());
        }
    }

    public record CountDto(String key, long count) {
    }

    /** IP có nhiều lần thất bại: tổng số lần và số tài khoản khác nhau bị thử. */
    public record IpDto(String ip, long failures, long accounts) {
    }

    public record SummaryDto(int hours, long total, long failures, long failedLogins, long lockedAccounts,
                             List<CountDto> byAction, List<CountDto> bySource, List<IpDto> suspiciousIps) {
    }
}
