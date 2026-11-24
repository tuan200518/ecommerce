package com.shop.audit.web;

import com.shop.audit.service.AuditService;
import com.shop.audit.service.AuditService.Filter;
import com.shop.audit.web.AuditDtos.AuditDto;
import com.shop.audit.web.AuditDtos.SummaryDto;
import com.shop.common.security.AuthenticatedUser;
import com.shop.support.web.PageResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Tra cứu nhật ký kiểm toán – chỉ đọc, chỉ ADMIN (Gateway chặn lớp 1, service kiểm tra lại lớp 2). */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<AuditDto> search(AuthenticatedUser user,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) String action,
                                         @RequestParam(required = false) String resourceType,
                                         @RequestParam(required = false) String resourceId,
                                         @RequestParam(required = false) String outcome,
                                         @RequestParam(required = false) String actor,
                                         @RequestParam(required = false) String source,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "50") int size) {
        return service.search(user, new Filter(q, action, resourceType, resourceId, outcome, actor, source, from, to),
                page, size);
    }

    @GetMapping("/summary")
    public SummaryDto summary(AuthenticatedUser user, @RequestParam(defaultValue = "24") int hours) {
        return service.summary(user, hours);
    }

    @GetMapping("/actions")
    public List<String> actions(AuthenticatedUser user) {
        return service.actions(user);
    }
}
