package com.shop.helpdesk.web;

import com.shop.common.security.AuthenticatedUser;
import com.shop.helpdesk.service.SupportService;
import com.shop.helpdesk.service.SupportService.QueueFilter;
import com.shop.helpdesk.web.SupportDtos.CreateTicketRequest;
import com.shop.helpdesk.web.SupportDtos.MessageDto;
import com.shop.helpdesk.web.SupportDtos.MessageRequest;
import com.shop.helpdesk.web.SupportDtos.OrderInfoDto;
import com.shop.helpdesk.web.SupportDtos.RateRequest;
import com.shop.helpdesk.web.SupportDtos.StaffUpdateRequest;
import com.shop.helpdesk.web.SupportDtos.SummaryDto;
import com.shop.helpdesk.web.SupportDtos.TicketDetailDto;
import com.shop.helpdesk.web.SupportDtos.TicketDto;
import com.shop.support.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/support")
public class SupportController {

    private final SupportService service;

    public SupportController(SupportService service) {
        this.service = service;
    }

    // --------------------------------------------- Khách hàng

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketDetailDto create(AuthenticatedUser user, @Valid @RequestBody CreateTicketRequest req) {
        return service.create(user, req);
    }

    @GetMapping("/tickets/mine")
    public PageResponse<TicketDto> mine(AuthenticatedUser user, @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return service.mine(user, page, size);
    }

    @GetMapping("/orders/mine")
    public List<OrderInfoDto> myOrders(AuthenticatedUser user) {
        return service.myOrders(user);
    }

    /** Chủ yêu cầu hoặc nhân viên CSKH / quản trị. */
    @GetMapping("/tickets/{id}")
    public TicketDetailDto detail(AuthenticatedUser user, @PathVariable long id) {
        return service.detail(user, id);
    }

    @PostMapping("/tickets/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageDto message(AuthenticatedUser user, @PathVariable long id, @Valid @RequestBody MessageRequest req) {
        return service.postMessage(user, id, req.content());
    }

    @PostMapping("/tickets/{id}/close")
    public TicketDto close(AuthenticatedUser user, @PathVariable long id) {
        return service.close(user, id);
    }

    @PostMapping("/tickets/{id}/rating")
    public TicketDto rate(AuthenticatedUser user, @PathVariable long id, @Valid @RequestBody RateRequest req) {
        return service.rate(user, id, req);
    }

    /** SSE: tin nhắn mới + thay đổi trạng thái của một yêu cầu (EventSource dùng ?access_token=). */
    @GetMapping(path = "/tickets/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(AuthenticatedUser user, @PathVariable long id) {
        return service.streamTicket(user, id);
    }

    // --------------------------------------------- Nhân viên CSKH

    @GetMapping("/admin/tickets")
    public PageResponse<TicketDto> queue(AuthenticatedUser user,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String priority,
                                         @RequestParam(required = false) String assignee,
                                         @RequestParam(required = false) Boolean overdue,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "30") int size) {
        return service.queue(user, new QueueFilter(status, priority, assignee, overdue, q), page, size);
    }

    @PutMapping("/admin/tickets/{id}")
    public TicketDto update(AuthenticatedUser user, @PathVariable long id, @Valid @RequestBody StaffUpdateRequest req) {
        return service.update(user, id, req);
    }

    @GetMapping("/admin/summary")
    public SummaryDto summary(AuthenticatedUser user) {
        return service.summary(user);
    }

    /** SSE hàng đợi: yêu cầu mới / cập nhật để danh sách của nhân viên tự làm mới. */
    @GetMapping(path = "/admin/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queueStream(AuthenticatedUser user) {
        return service.streamQueue(user);
    }
}
