package com.shop.fraud.web;

import com.shop.common.security.AuthenticatedUser;
import com.shop.fraud.service.FraudService;
import com.shop.fraud.web.FraudDtos.ReviewRequest;
import com.shop.fraud.web.FraudDtos.RiskCaseDto;
import com.shop.fraud.web.FraudDtos.SummaryDto;
import com.shop.support.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Quản lý rủi ro – chỉ ADMIN / ANALYST (Gateway chặn trước, service kiểm tra lại). */
@RestController
@RequestMapping("/api/fraud")
public class FraudController {

    private final FraudService service;

    public FraudController(FraudService service) {
        this.service = service;
    }

    @GetMapping("/cases")
    public PageResponse<RiskCaseDto> cases(AuthenticatedUser user,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String level,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return service.list(user, status, level, page, size);
    }

    @GetMapping("/cases/{orderId}")
    public RiskCaseDto get(AuthenticatedUser user, @PathVariable long orderId) {
        return service.get(user, orderId);
    }

    @PostMapping("/cases/{orderId}/review")
    public RiskCaseDto review(AuthenticatedUser user, @PathVariable long orderId,
                              @Valid @RequestBody ReviewRequest req) {
        return service.review(user, orderId, req.approved(), req.note());
    }

    @GetMapping("/summary")
    public SummaryDto summary(AuthenticatedUser user) {
        return service.summary(user);
    }
}
