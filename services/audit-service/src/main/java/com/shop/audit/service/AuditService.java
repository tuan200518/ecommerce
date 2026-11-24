package com.shop.audit.service;

import com.shop.audit.AuditProperties;
import com.shop.audit.domain.AuditEntry;
import com.shop.audit.domain.AuditEntryRepository;
import com.shop.audit.web.AuditDtos.AuditDto;
import com.shop.audit.web.AuditDtos.CountDto;
import com.shop.audit.web.AuditDtos.IpDto;
import com.shop.audit.web.AuditDtos.SummaryDto;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.AuditEvents.AuditRecorded;
import com.shop.common.event.payload.NotificationEvents.NotificationRequested;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.support.kafka.EventReader;
import com.shop.support.outbox.IdempotencyGuard;
import com.shop.support.outbox.OutboxService;
import com.shop.support.web.Access;
import com.shop.support.web.ApiException;
import com.shop.support.web.PageResponse;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    static final String CONSUMER = "audit-service";
    private static final int MAX_RANGE_DAYS = 90;

    private final AuditEntryRepository entries;
    private final IdempotencyGuard guard;
    private final EventReader reader;
    private final OutboxService outbox;
    private final AuditProperties props;

    public AuditService(AuditEntryRepository entries, IdempotencyGuard guard, EventReader reader,
                        OutboxService outbox, AuditProperties props) {
        this.entries = entries;
        this.guard = guard;
        this.reader = reader;
        this.outbox = outbox;
        this.props = props;
    }

    // ------------------------------------------------------------------ Kafka

    @Transactional
    public void handleAuditEvent(EventEnvelope env) {
        if (!env.is(EventTypes.AUDIT_RECORDED) || !guard.firstTime(env, CONSUMER)
                || entries.existsByEventId(env.eventId())) {
            return;
        }
        AuditRecorded p = reader.payload(env, AuditRecorded.class);
        Instant at = env.occurredAt() == null ? Instant.now() : env.occurredAt();
        entries.save(new AuditEntry(env.eventId(), at, env.source() == null ? "unknown" : env.source(),
                env.correlationId(), p.actorId(), p.actorEmail(), p.actorRoles(), p.action(),
                p.resourceType() == null ? "UNKNOWN" : p.resourceType(), p.resourceId(),
                p.outcome() == null ? "SUCCESS" : p.outcome(), p.detail(), p.clientIp()));

        if (Duration.between(at, Instant.now()).compareTo(props.alertMaxAge()) <= 0) {
            securityAlerts(p, at);
        }
    }

    /** Cảnh báo bảo mật cho quản trị viên (chỉ với sự kiện mới, không lặp lại khi đọc lại topic). */
    private void securityAlerts(AuditRecorded p, Instant at) {
        if ("ACCOUNT_LOCKED".equals(p.action())) {
            notifyAdmins("SECURITY_ACCOUNT_LOCKED", "Tài khoản bị tạm khóa: " + p.actorEmail(),
                    "Đăng nhập sai mật khẩu nhiều lần liên tiếp" + from(p.clientIp()) + ".", "audit:user:" + p.actorId());
        }
        if (("LOGIN_FAILED".equals(p.action()) || "ACCOUNT_LOCKED".equals(p.action())) && hasIp(p.clientIp())) {
            long emails = entries.countDistinctFailedEmailsFromIp(p.clientIp(), at.minus(props.suspiciousIpWindow()));
            // Chỉ cảnh báo đúng lúc vượt ngưỡng → một cảnh báo cho mỗi đợt tấn công
            if (emails == props.suspiciousIpEmails()) {
                log.warn("Nghi ngờ dò mật khẩu hàng loạt từ IP {} ({} tài khoản)", p.clientIp(), emails);
                notifyAdmins("SECURITY_CREDENTIAL_STUFFING", "Nghi ngờ dò mật khẩu từ IP " + p.clientIp(),
                        "Đăng nhập sai vào " + emails + " tài khoản khác nhau trong "
                                + props.suspiciousIpWindow().toMinutes() + " phút.", "audit:ip:" + p.clientIp());
            }
        }
    }

    private void notifyAdmins(String type, String title, String message, String ref) {
        outbox.publish(Topics.NOTIFICATION_EVENTS, EventTypes.NOTIFICATION_REQUESTED, "role-" + Roles.ADMIN,
                new NotificationRequested(null, Roles.ADMIN, type, title, message, ref));
    }

    private static boolean hasIp(String ip) {
        return ip != null && !ip.isBlank() && !"unknown".equals(ip);
    }

    private static String from(String ip) {
        return hasIp(ip) ? " từ IP " + ip : "";
    }

    // ------------------------------------------------------------------ REST (chỉ ADMIN)

    public record Filter(String q, String action, String resourceType, String resourceId, String outcome,
                         String actor, String source, Instant from, Instant to) {
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditDto> search(AuthenticatedUser user, Filter f, int page, int size) {
        Access.requireAnyRole(user, Roles.ADMIN);
        if (f.from() != null && f.to() != null && f.from().isAfter(f.to())) {
            throw ApiException.badRequest("Khoảng thời gian không hợp lệ");
        }
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                Sort.by(Sort.Direction.DESC, "occurredAt", "id"));
        return PageResponse.of(entries.findAll(spec(f), pr), AuditDto::from);
    }

    @Transactional(readOnly = true)
    public SummaryDto summary(AuthenticatedUser user, int hours) {
        Access.requireAnyRole(user, Roles.ADMIN);
        int h = Math.min(Math.max(hours, 1), MAX_RANGE_DAYS * 24);
        Instant since = Instant.now().minus(Duration.ofHours(h));
        return new SummaryDto(h,
                entries.countByOccurredAtGreaterThanEqual(since),
                entries.countByOutcomeAndOccurredAtGreaterThanEqual("FAILURE", since),
                entries.countByActionAndOccurredAtGreaterThanEqual("LOGIN_FAILED", since),
                entries.countByActionAndOccurredAtGreaterThanEqual("ACCOUNT_LOCKED", since),
                counts(entries.countByAction(since), 15),
                counts(entries.countBySource(since), 20),
                entries.failuresByIp(since).stream().limit(10)
                        .map(r -> new IpDto((String) r[0], ((Number) r[1]).longValue(), ((Number) r[2]).longValue()))
                        .toList());
    }

    @Transactional(readOnly = true)
    public List<String> actions(AuthenticatedUser user) {
        Access.requireAnyRole(user, Roles.ADMIN);
        return entries.distinctActions();
    }

    private static List<CountDto> counts(List<Object[]> rows, int limit) {
        return rows.stream().limit(limit).map(r -> new CountDto((String) r[0], ((Number) r[1]).longValue())).toList();
    }

    private static Specification<AuditEntry> spec(Filter f) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (notBlank(f.action())) {
                ps.add(cb.equal(root.get("action"), f.action().trim().toUpperCase(Locale.ROOT)));
            }
            if (notBlank(f.resourceType())) {
                ps.add(cb.equal(root.get("resourceType"), f.resourceType().trim().toUpperCase(Locale.ROOT)));
            }
            if (notBlank(f.resourceId())) {
                ps.add(cb.equal(root.get("resourceId"), f.resourceId().trim()));
            }
            if (notBlank(f.outcome())) {
                ps.add(cb.equal(root.get("outcome"), f.outcome().trim().toUpperCase(Locale.ROOT)));
            }
            if (notBlank(f.source())) {
                ps.add(cb.equal(root.get("source"), f.source().trim()));
            }
            if (notBlank(f.actor())) {
                ps.add(cb.like(cb.lower(root.get("actorEmail")), "%" + escape(f.actor()) + "%", '\\'));
            }
            if (notBlank(f.q())) {
                String like = "%" + escape(f.q()) + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("detail")), like, '\\'),
                        cb.like(cb.lower(root.get("actorEmail")), like, '\\'),
                        cb.like(cb.lower(root.get("clientIp")), like, '\\'),
                        cb.like(cb.lower(root.get("correlationId")), like, '\\')));
            }
            if (f.from() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), f.from()));
            }
            if (f.to() != null) {
                ps.add(cb.lessThan(root.get("occurredAt"), f.to()));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Chuẩn hóa chuỗi tìm kiếm cho LIKE (thoát ký tự đặc biệt % _ \). */
    static String escape(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
