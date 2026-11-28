package com.shop.helpdesk.service;

import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.NotificationEvents.NotificationRequested;
import com.shop.common.event.payload.OrderEvents.OrderCreated;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.event.payload.SupportEvents.TicketChanged;
import com.shop.common.event.payload.SupportEvents.TicketMessagePosted;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.helpdesk.SupportProperties;
import com.shop.helpdesk.domain.OrderRef;
import com.shop.helpdesk.domain.OrderRefRepository;
import com.shop.helpdesk.domain.Ticket;
import com.shop.helpdesk.domain.Ticket.Status;
import com.shop.helpdesk.domain.TicketMessage;
import com.shop.helpdesk.domain.TicketMessage.Kind;
import com.shop.helpdesk.domain.TicketMessageRepository;
import com.shop.helpdesk.domain.TicketRepository;
import com.shop.helpdesk.web.SupportDtos.CreateTicketRequest;
import com.shop.helpdesk.web.SupportDtos.MessageDto;
import com.shop.helpdesk.web.SupportDtos.OrderInfoDto;
import com.shop.helpdesk.web.SupportDtos.RateRequest;
import com.shop.helpdesk.web.SupportDtos.StaffUpdateRequest;
import com.shop.helpdesk.web.SupportDtos.SummaryDto;
import com.shop.helpdesk.web.SupportDtos.TicketDetailDto;
import com.shop.helpdesk.web.SupportDtos.TicketDto;
import com.shop.support.audit.AuditTrail;
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
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SupportService {

    private static final Logger log = LoggerFactory.getLogger(SupportService.class);
    static final String CONSUMER = "support-service";
    public static final Set<Status> ACTIVE = EnumSet.of(Status.OPEN, Status.IN_PROGRESS, Status.WAITING_CUSTOMER);
    static final String[] AGENTS = {Roles.ADMIN, Roles.SUPPORT_AGENT};
    static final String[] VIEWERS = {Roles.ADMIN, Roles.SUPPORT_AGENT, Roles.ANALYST};
    private static final BigDecimal BIG_ORDER = new BigDecimal("10000000");

    static final Map<Status, String> STATUS_LABEL = Map.of(Status.OPEN, "Chờ xử lý", Status.IN_PROGRESS, "Đang xử lý",
            Status.WAITING_CUSTOMER, "Chờ khách phản hồi", Status.RESOLVED, "Đã giải quyết", Status.CLOSED, "Đã đóng");
    private static final Map<String, String> ORDER_STATUS_LABEL = Map.of("PENDING", "Chờ xử lý",
            "UNDER_REVIEW", "Đang xác minh", "AWAITING_PAYMENT", "Chờ thanh toán", "CONFIRMED", "Đã xác nhận",
            "SHIPPING", "Đang giao", "DELIVERED", "Đã giao", "CANCELLED", "Đã hủy");

    private final TicketRepository tickets;
    private final TicketMessageRepository messages;
    private final OrderRefRepository orders;
    private final OutboxService outbox;
    private final IdempotencyGuard guard;
    private final EventReader reader;
    private final AuditTrail audit;
    private final SupportHub hub;
    private final SupportProperties props;

    public SupportService(TicketRepository tickets, TicketMessageRepository messages, OrderRefRepository orders,
                          OutboxService outbox, IdempotencyGuard guard, EventReader reader, AuditTrail audit,
                          SupportHub hub, SupportProperties props) {
        this.tickets = tickets;
        this.messages = messages;
        this.orders = orders;
        this.outbox = outbox;
        this.guard = guard;
        this.reader = reader;
        this.audit = audit;
        this.hub = hub;
        this.props = props;
    }

    // ------------------------------------------------------------------ Khách hàng

    @Transactional
    public TicketDetailDto create(AuthenticatedUser user, CreateTicketRequest req) {
        if (!user.hasRole(Roles.CUSTOMER)) {
            throw ApiException.forbidden("Chỉ tài khoản khách hàng được tạo yêu cầu hỗ trợ");
        }
        if (tickets.countByUserIdAndStatusIn(user.userId(), ACTIVE) >= props.maxOpenPerCustomer()) {
            throw ApiException.conflict("Bạn đang có " + props.maxOpenPerCustomer()
                    + " yêu cầu chưa xử lý xong – vui lòng tiếp tục trong các yêu cầu đó");
        }
        OrderRef order = null;
        if (req.orderId() != null) {
            order = orders.findById(req.orderId()).filter(o -> o.getUserId().equals(user.userId()))
                    .orElseThrow(() -> ApiException.badRequest("Không tìm thấy đơn hàng #" + req.orderId() + " của bạn"));
        }
        Ticket.Category category = Ticket.Category.valueOf(req.category());
        Ticket.Priority priority = category == Ticket.Category.PAYMENT
                || (order != null && order.getTotalAmount() != null && order.getTotalAmount().compareTo(BIG_ORDER) >= 0)
                ? Ticket.Priority.HIGH : Ticket.Priority.NORMAL;
        Instant now = Instant.now();
        Ticket t = tickets.save(new Ticket(user.userId(), user.email() == null ? "" : user.email(), req.subject().trim(),
                category, req.orderId(), priority, now.plus(props.slaFor(priority))));
        TicketMessage first = messages.save(new TicketMessage(t.getId(), user.userId(), "Khách hàng", Kind.CUSTOMER,
                req.message().trim()));
        t.customerReplied();
        publish(EventTypes.TICKET_CREATED, t, null);
        notifyRole(Roles.SUPPORT_AGENT, "SUPPORT_TICKET_NEW", "Yêu cầu hỗ trợ mới " + t.code(),
                "[" + category + (priority == Ticket.Priority.HIGH ? ", ưu tiên cao" : "") + "] " + t.getSubject(), t);
        TicketDto staffView = TicketDto.from(t, true);
        afterCommit(() -> hub.toQueue("ticket", staffView));
        log.info("Khách {} tạo yêu cầu {} ({}, {})", user.userId(), t.code(), category, priority);
        return new TicketDetailDto(TicketDto.from(t, false), List.of(MessageDto.from(first)), OrderInfoDto.from(order));
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketDto> mine(AuthenticatedUser user, int page, int size) {
        return PageResponse.of(tickets.findByUserIdOrderByUpdatedAtDesc(user.userId(), pageable(page, size)),
                t -> TicketDto.from(t, false));
    }

    /** Đơn gần đây của khách (để chọn khi tạo yêu cầu). */
    @Transactional(readOnly = true)
    public List<OrderInfoDto> myOrders(AuthenticatedUser user) {
        return orders.findTop20ByUserIdOrderByOrderIdDesc(user.userId()).stream().map(OrderInfoDto::from).toList();
    }

    /** Chi tiết + toàn bộ tin nhắn; đánh dấu đã đọc theo góc nhìn người xem. */
    @Transactional
    public TicketDetailDto detail(AuthenticatedUser user, long id) {
        Ticket t = loadFor(user, id);
        boolean staff = isViewerStaff(user, t);
        if (staff) {
            t.readByStaff();
        } else {
            t.readByCustomer();
        }
        return new TicketDetailDto(TicketDto.from(t, staff),
                messages.findByTicketIdOrderByIdAsc(id).stream().map(MessageDto::from).toList(),
                t.getOrderId() == null ? null : OrderInfoDto.from(orders.findById(t.getOrderId()).orElse(null)));
    }

    @Transactional
    public MessageDto postMessage(AuthenticatedUser user, long id, String content) {
        Ticket t = loadFor(user, id);
        if (t.getStatus() == Status.CLOSED) {
            throw ApiException.conflict("Yêu cầu " + t.code() + " đã đóng – vui lòng tạo yêu cầu mới");
        }
        boolean staff = isViewerStaff(user, t);
        Status previous = t.getStatus();
        TicketMessage m;
        if (staff) {
            Access.requireAnyRole(user, AGENTS);
            m = messages.save(new TicketMessage(id, user.userId(), staffName(user), Kind.STAFF, content.trim()));
            t.staffReplied(user.userId(), user.email());
            if (previous == Status.OPEN) {
                t.changeStatus(Status.IN_PROGRESS);
            }
            outbox.publish(Topics.NOTIFICATION_EVENTS, EventTypes.NOTIFICATION_REQUESTED, "user-" + t.getUserId(),
                    new NotificationRequested(t.getUserId(), null, "SUPPORT_REPLY",
                            "CSKH đã trả lời yêu cầu " + t.code(), abbreviate(content.trim(), 160), "support:" + id));
        } else {
            m = messages.save(new TicketMessage(id, user.userId(), "Khách hàng", Kind.CUSTOMER, content.trim()));
            t.customerReplied();
            // Đã có người phụ trách → tiếp tục xử lý; chưa có → quay về hàng đợi chờ nhận
            Status resumed = t.getAssigneeId() == null ? Status.OPEN : Status.IN_PROGRESS;
            if (previous == Status.RESOLVED) {
                t.changeStatus(resumed);
                system(t, "Khách hàng mở lại yêu cầu");
            } else if (previous == Status.WAITING_CUSTOMER) {
                t.changeStatus(resumed);
            }
            if (t.getAssigneeId() != null) {
                outbox.publish(Topics.NOTIFICATION_EVENTS, EventTypes.NOTIFICATION_REQUESTED, "user-" + t.getAssigneeId(),
                        new NotificationRequested(t.getAssigneeId(), null, "SUPPORT_CUSTOMER_REPLY",
                                "Khách trả lời yêu cầu " + t.code(), abbreviate(content.trim(), 160), "support:" + id));
            }
        }
        if (previous != t.getStatus()) {
            publish(EventTypes.TICKET_STATUS_CHANGED, t, previous);
        }
        outbox.publish(Topics.SUPPORT_EVENTS, EventTypes.TICKET_MESSAGE_POSTED, String.valueOf(id),
                new TicketMessagePosted(id, t.code(), t.getUserId(), user.userId(), staff, abbreviate(content.trim(), 80)));
        MessageDto dto = MessageDto.from(m);
        pushAfterCommit(t, dto);
        return dto;
    }

    /** Khách tự đóng yêu cầu (vấn đề đã được giải quyết). */
    @Transactional
    public TicketDto close(AuthenticatedUser user, long id) {
        Ticket t = ownTicket(user, id);
        if (t.getStatus() == Status.CLOSED) {
            return TicketDto.from(t, false);
        }
        Status previous = t.changeStatus(Status.CLOSED);
        system(t, "Khách hàng đã đóng yêu cầu");
        publish(EventTypes.TICKET_STATUS_CHANGED, t, previous);
        pushAfterCommit(t, null);
        return TicketDto.from(t, false);
    }

    @Transactional
    public TicketDto rate(AuthenticatedUser user, long id, RateRequest req) {
        Ticket t = ownTicket(user, id);
        if (t.getStatus().isActive()) {
            throw ApiException.conflict("Chỉ đánh giá được sau khi yêu cầu đã được giải quyết");
        }
        if (t.getRating() != null) {
            throw ApiException.conflict("Bạn đã đánh giá yêu cầu này");
        }
        t.rate(req.rating(), req.comment() == null || req.comment().isBlank() ? null : req.comment().trim());
        system(t, "Khách hàng đánh giá " + req.rating() + "/5" + (t.getRatingComment() == null ? "" : ": " + t.getRatingComment()));
        pushAfterCommit(t, null);
        return TicketDto.from(t, false);
    }

    @Transactional(readOnly = true)
    public SseEmitter streamTicket(AuthenticatedUser user, long id) {
        loadFor(user, id);
        return hub.subscribeTicket(id);
    }

    // ------------------------------------------------------------------ Nhân viên CSKH

    public record QueueFilter(String status, String priority, String assignee, Boolean overdue, String q) {
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketDto> queue(AuthenticatedUser user, QueueFilter f, int page, int size) {
        Access.requireAnyRole(user, VIEWERS);
        Instant now = Instant.now();
        Specification<Ticket> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (notBlank(f.status())) {
                if ("ACTIVE".equals(f.status())) {
                    ps.add(root.get("status").in(ACTIVE));
                } else {
                    ps.add(cb.equal(root.get("status"), parse(Status.class, f.status(), "Trạng thái")));
                }
            }
            if (notBlank(f.priority())) {
                ps.add(cb.equal(root.get("priority"), parse(Ticket.Priority.class, f.priority(), "Mức ưu tiên")));
            }
            if ("ME".equals(f.assignee())) {
                ps.add(cb.equal(root.get("assigneeId"), user.userId()));
            } else if ("NONE".equals(f.assignee())) {
                ps.add(cb.isNull(root.get("assigneeId")));
            }
            if (Boolean.TRUE.equals(f.overdue())) {
                ps.add(root.get("status").in(ACTIVE));
                ps.add(cb.isNull(root.get("firstResponseAt")));
                ps.add(cb.lessThan(root.get("firstResponseDueAt"), now));
            }
            if (notBlank(f.q())) {
                String like = "%" + f.q().trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
                        .replace("_", "\\_") + "%";
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("subject")), like, '\\'));
                any.add(cb.like(cb.lower(root.get("customerEmail")), like, '\\'));
                String digits = f.q().replaceAll("\\D", "");
                if (!digits.isEmpty() && digits.length() < 15) {
                    long n = Long.parseLong(digits);
                    any.add(cb.equal(root.get("id"), n));
                    any.add(cb.equal(root.get("orderId"), n));
                }
                ps.add(cb.or(any.toArray(Predicate[]::new)));
            }
            // Sắp xếp theo mức ưu tiên thật (cột lưu chuỗi nên không sắp theo tên được) rồi tin nhắn mới nhất.
            // Bỏ qua với câu đếm của phân trang.
            Class<?> type = query.getResultType();
            if (type != Long.class && type != long.class) {
                query.orderBy(cb.asc(cb.selectCase(root.get("priority"))
                                .when(Ticket.Priority.URGENT, 0).when(Ticket.Priority.HIGH, 1)
                                .when(Ticket.Priority.NORMAL, 2).otherwise(3)),
                        cb.desc(root.get("lastMessageAt")));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return PageResponse.of(tickets.findAll(spec, pr), t -> TicketDto.from(t, true));
    }

    @Transactional
    public TicketDto update(AuthenticatedUser user, long id, StaffUpdateRequest req) {
        Access.requireAnyRole(user, AGENTS);
        Ticket t = tickets.findById(id).orElseThrow(() -> ApiException.notFound("Không tìm thấy yêu cầu hỗ trợ"));
        List<String> changes = new ArrayList<>();
        if ("ME".equals(req.assignee()) && !Long.valueOf(user.userId()).equals(t.getAssigneeId())) {
            t.assign(user.userId(), user.email());
            changes.add("nhận xử lý: " + user.email());
        } else if ("NONE".equals(req.assignee()) && t.getAssigneeId() != null) {
            t.assign(null, null);
            changes.add("bỏ phân công");
        }
        if (notBlank(req.priority())) {
            Ticket.Priority p = Ticket.Priority.valueOf(req.priority());
            if (p != t.getPriority()) {
                changes.add("ưu tiên " + t.getPriority() + " → " + p);
                t.changePriority(p, t.getCreatedAt().plus(props.slaFor(p)));
            }
        }
        Status previous = t.getStatus();
        if (notBlank(req.status())) {
            Status next = Status.valueOf(req.status());
            if (!Ticket.STAFF_SETTABLE.contains(next)) {
                throw ApiException.badRequest("Không thể đặt trạng thái " + next);
            }
            if (previous == Status.CLOSED && next != Status.CLOSED) {
                throw ApiException.conflict("Yêu cầu đã đóng");
            }
            if (next != previous) {
                t.changeStatus(next);
                changes.add(STATUS_LABEL.get(previous) + " → " + STATUS_LABEL.get(next));
                if (next == Status.RESOLVED) {
                    outbox.publish(Topics.NOTIFICATION_EVENTS, EventTypes.NOTIFICATION_REQUESTED, "user-" + t.getUserId(),
                            new NotificationRequested(t.getUserId(), null, "SUPPORT_RESOLVED",
                                    "Yêu cầu " + t.code() + " đã được giải quyết",
                                    "Hãy đánh giá mức độ hài lòng của bạn. Nếu vấn đề chưa xong, chỉ cần trả lời để mở lại.",
                                    "support:" + id));
                }
            }
        }
        if (changes.isEmpty()) {
            return TicketDto.from(t, true);
        }
        String note = notBlank(req.note()) ? " – " + req.note().trim() : "";
        system(t, staffName(user) + ": " + String.join("; ", changes) + note);
        if (previous != t.getStatus()) {
            publish(EventTypes.TICKET_STATUS_CHANGED, t, previous);
        }
        audit.record(user, "SUPPORT_TICKET_UPDATED", "TICKET", id, String.join("; ", changes) + note);
        pushAfterCommit(t, null);
        return TicketDto.from(t, true);
    }

    @Transactional(readOnly = true)
    public SummaryDto summary(AuthenticatedUser user) {
        Access.requireAnyRole(user, VIEWERS);
        Instant now = Instant.now();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Status s : Status.values()) {
            byStatus.put(s.name(), tickets.countByStatus(s));
        }
        long active = ACTIVE.stream().mapToLong(s -> byStatus.getOrDefault(s.name(), 0L)).sum();
        Instant startOfDay = now.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        Instant week = now.minus(Duration.ofDays(7));
        List<Ticket> responded = tickets.findResponded(week);
        Double avgFirst = responded.isEmpty() ? null : Math.round(responded.stream()
                .mapToLong(t -> Duration.between(t.getCreatedAt(), t.getFirstResponseAt()).toSeconds())
                .average().orElse(0) / 60.0 * 10) / 10.0;
        Object[] rating = tickets.ratingStats(now.minus(Duration.ofDays(30))).stream().findFirst().orElse(new Object[]{null, 0L});
        Double avgRating = rating[0] == null ? null : Math.round(((Number) rating[0]).doubleValue() * 10) / 10.0;
        return new SummaryDto(byStatus, active, tickets.countByStatusInAndAssigneeIdIsNull(ACTIVE),
                tickets.countOverdue(ACTIVE, now),
                tickets.countByStatusInAndResolvedAtGreaterThanEqual(EnumSet.of(Status.RESOLVED, Status.CLOSED), startOfDay),
                avgFirst, avgRating, rating[1] == null ? 0 : ((Number) rating[1]).longValue());
    }

    public SseEmitter streamQueue(AuthenticatedUser user) {
        Access.requireAnyRole(user, VIEWERS);
        return hub.subscribeQueue();
    }

    // ------------------------------------------------------------------ Kafka: read-model đơn hàng

    @Transactional
    public void handleOrderEvent(EventEnvelope env) {
        Long orderId;
        Long userId;
        String status;
        BigDecimal total;
        String note = null;
        switch (env.eventType()) {
            case EventTypes.ORDER_CREATED -> {
                OrderCreated p = reader.payload(env, OrderCreated.class);
                orderId = p.orderId();
                userId = p.userId();
                status = "PENDING";
                total = p.totalAmount();
            }
            case EventTypes.ORDER_STATUS_CHANGED, EventTypes.ORDER_CONFIRMED, EventTypes.ORDER_CANCELLED -> {
                OrderStatusChanged p = reader.payload(env, OrderStatusChanged.class);
                orderId = p.orderId();
                userId = p.userId();
                status = p.status();
                total = p.totalAmount();
                note = p.reason();
            }
            default -> {
                return;
            }
        }
        if (status == null) {
            return;
        }
        OrderRef ref = orders.findById(orderId).orElse(null);
        boolean changed = ref == null || !status.equals(ref.getStatus());
        if (ref == null) {
            ref = new OrderRef(orderId, userId);
        }
        ref.update(status, total);
        orders.save(ref);

        // Báo cho người đang xử lý yêu cầu liên quan (chỉ với sự kiện mới, không lặp lại khi đọc lại topic)
        Instant at = env.occurredAt() == null ? Instant.now() : env.occurredAt();
        boolean recent = Duration.between(at, Instant.now()).compareTo(props.maxEventAge()) <= 0;
        if (changed && recent && guard.firstTime(env, CONSUMER)) {
            for (Ticket t : tickets.findByOrderIdAndStatusIn(orderId, ACTIVE)) {
                system(t, "Đơn hàng #" + orderId + " chuyển sang trạng thái \"" + ORDER_STATUS_LABEL.getOrDefault(status, status)
                        + "\"" + ("CANCELLED".equals(status) && note != null ? ": " + note : ""));
                pushAfterCommit(t, null);
            }
        }
    }

    // ------------------------------------------------------------------ Tác vụ định kỳ (gọi từ SupportJobs)

    /** Yêu cầu vừa quá hạn phản hồi đầu tiên → báo nhân viên CSKH (mỗi yêu cầu một lần). */
    @Transactional
    public int notifySlaBreaches() {
        List<Ticket> breached = tickets.findNewlyOverdue(ACTIVE, Instant.now());
        for (Ticket t : breached) {
            t.markSlaBreachNotified();
            notifyRole(Roles.SUPPORT_AGENT, "SUPPORT_SLA_BREACH", "Quá hạn phản hồi: " + t.code(),
                    "[" + t.getPriority() + "] " + t.getSubject() + " – chưa có phản hồi đầu tiên", t);
            pushAfterCommit(t, null);
        }
        return breached.size();
    }

    /** Yêu cầu đã giải quyết, khách không phản hồi sau {@code autoCloseAfter} → tự đóng. */
    @Transactional
    public int autoClose() {
        List<Ticket> stale = tickets.findTop100ByStatusAndResolvedAtBefore(Status.RESOLVED,
                Instant.now().minus(props.autoCloseAfter()));
        for (Ticket t : stale) {
            Status previous = t.changeStatus(Status.CLOSED);
            system(t, "Tự động đóng sau " + props.autoCloseAfter().toDays() + " ngày không có phản hồi");
            publish(EventTypes.TICKET_STATUS_CHANGED, t, previous);
        }
        return stale.size();
    }

    // ------------------------------------------------------------------ Nội bộ

    private Ticket loadFor(AuthenticatedUser user, long id) {
        Ticket t = tickets.findById(id).orElseThrow(() -> ApiException.notFound("Không tìm thấy yêu cầu hỗ trợ"));
        if (!t.getUserId().equals(user.userId()) && !user.hasAnyRole(VIEWERS)) {
            throw ApiException.notFound("Không tìm thấy yêu cầu hỗ trợ"); // không tiết lộ yêu cầu của người khác
        }
        return t;
    }

    private Ticket ownTicket(AuthenticatedUser user, long id) {
        Ticket t = tickets.findById(id).orElseThrow(() -> ApiException.notFound("Không tìm thấy yêu cầu hỗ trợ"));
        if (!t.getUserId().equals(user.userId())) {
            throw ApiException.notFound("Không tìm thấy yêu cầu hỗ trợ");
        }
        return t;
    }

    /** Nhân viên xem yêu cầu của người khác (chủ yêu cầu luôn được xem như khách, kể cả khi có vai trò nhân viên). */
    private static boolean isViewerStaff(AuthenticatedUser user, Ticket t) {
        return !t.getUserId().equals(user.userId()) && user.hasAnyRole(VIEWERS);
    }

    private void system(Ticket t, String content) {
        TicketMessage m = messages.save(new TicketMessage(t.getId(), null, "Hệ thống", Kind.SYSTEM, abbreviate(content, 2000)));
        MessageDto dto = MessageDto.from(m);
        afterCommit(() -> hub.toTicket(t.getId(), "message", dto));
    }

    private void publish(String type, Ticket t, Status previous) {
        outbox.publish(Topics.SUPPORT_EVENTS, type, String.valueOf(t.getId()),
                new TicketChanged(t.getId(), t.code(), t.getUserId(), t.getOrderId(), t.getCategory().name(),
                        t.getPriority().name(), previous == null ? null : previous.name(), t.getStatus().name(),
                        t.getAssigneeEmail()));
    }

    private void notifyRole(String role, String type, String title, String message, Ticket t) {
        outbox.publish(Topics.NOTIFICATION_EVENTS, EventTypes.NOTIFICATION_REQUESTED, "role-" + role,
                new NotificationRequested(null, role, type, title, abbreviate(message, 300), "support:" + t.getId()));
    }

    /** Đẩy tin nhắn (nếu có) + trạng thái mới tới người đang mở yêu cầu và hàng đợi CSKH, sau khi commit. */
    private void pushAfterCommit(Ticket t, MessageDto message) {
        TicketDto forTicket = TicketDto.from(t, false);
        TicketDto forQueue = TicketDto.from(t, true);
        afterCommit(() -> {
            if (message != null) {
                hub.toTicket(t.getId(), "message", message);
            }
            hub.toTicket(t.getId(), "ticket", forTicket);
            hub.toQueue("ticket", forQueue);
        });
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }

    static String staffName(AuthenticatedUser user) {
        String email = user.email() == null ? "" : user.email();
        int at = email.indexOf('@');
        return "CSKH · " + (at > 0 ? email.substring(0, at) : "nhân viên");
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String label) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(label + " không hợp lệ: " + value);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String abbreviate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static PageRequest pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }
}
