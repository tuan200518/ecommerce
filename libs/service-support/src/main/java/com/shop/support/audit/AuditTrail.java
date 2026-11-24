package com.shop.support.audit;

import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.AuditEvents.AuditRecorded;
import com.shop.common.security.AuthHeaders;
import com.shop.common.security.AuthenticatedUser;
import com.shop.support.outbox.OutboxService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.TreeSet;

/**
 * Ghi nhật ký kiểm toán (audit log) qua outbox → topic audit-events → audit-service lưu trữ và tra cứu.
 * <ul>
 *   <li>{@link #record}: gọi TRONG transaction nghiệp vụ → nhật ký tồn tại khi và chỉ khi thao tác được commit.</li>
 *   <li>{@link #recordFailure}: transaction riêng (REQUIRES_NEW) → vẫn ghi được khi thao tác chính bị rollback,
 *       vd. đăng nhập sai mật khẩu.</li>
 * </ul>
 */
public class AuditTrail {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";
    private static final int MAX_DETAIL = 1000;

    private final OutboxService outbox;

    public AuditTrail(OutboxService outbox) {
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuthenticatedUser actor, String action, String resourceType, Object resourceId, String detail) {
        publish(actor == null ? null : actor.userId(), actor == null ? null : actor.email(),
                actor == null ? null : String.join(",", new TreeSet<>(actor.roles())),
                action, resourceType, resourceId, SUCCESS, detail);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long actorId, String actorEmail, String action, String resourceType, Object resourceId,
                              String detail) {
        publish(actorId, actorEmail, null, action, resourceType, resourceId, FAILURE, detail);
    }

    private void publish(Long actorId, String email, String roles, String action, String resourceType,
                         Object resourceId, String outcome, String detail) {
        String id = resourceId == null ? null : resourceId.toString();
        AuditRecorded payload = new AuditRecorded(actorId, email, roles, action, resourceType, id, outcome,
                truncate(detail), currentClientIp());
        // key = đối tượng bị tác động → mọi thao tác trên cùng đối tượng nằm trên một partition, đúng thứ tự
        String key = resourceType + ":" + (id == null ? "-" : id);
        outbox.publish(Topics.AUDIT_EVENTS, EventTypes.AUDIT_RECORDED, key, payload);
    }

    private static String truncate(String s) {
        return s == null || s.length() <= MAX_DETAIL ? s : s.substring(0, MAX_DETAIL - 1) + "…";
    }

    /** IP client do Gateway gắn vào header (null khi không ở trong request HTTP, vd. trong Kafka listener). */
    static String currentClientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            String ip = request.getHeader(AuthHeaders.CLIENT_IP);
            if (ip != null && !ip.isBlank()) {
                return ip.length() > 64 ? ip.substring(0, 64) : ip.trim();
            }
            return request.getRemoteAddr();
        }
        return null;
    }
}
