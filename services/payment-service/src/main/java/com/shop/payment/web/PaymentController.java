package com.shop.payment.web;

import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.payment.domain.Payment;
import com.shop.payment.domain.PaymentRepository;
import com.shop.payment.domain.PaymentTransactionRepository;
import com.shop.payment.domain.PaymentTransaction;
import com.shop.support.web.Access;
import com.shop.support.web.ApiException;
import com.shop.support.web.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;

    public PaymentController(PaymentRepository payments, PaymentTransactionRepository transactions) {
        this.payments = payments;
        this.transactions = transactions;
    }

    public record PaymentDto(long id, long orderId, long userId, BigDecimal amount, String method, String status,
                             String failureReason, Instant createdAt, Instant updatedAt) {
        static PaymentDto from(Payment p) {
            return new PaymentDto(p.getId(), p.getOrderId(), p.getUserId(), p.getAmount(), p.getMethod(),
                    p.getStatus().name(), p.getFailureReason(), p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record TransactionDto(long id, String type, boolean success, BigDecimal amount, String providerRef,
                                 String message, Instant createdAt) {
        static TransactionDto from(PaymentTransaction t) {
            return new TransactionDto(t.getId(), t.getType().name(), t.isSuccess(), t.getAmount(),
                    t.getProviderRef(), t.getMessage(), t.getCreatedAt());
        }
    }

    public record PaymentDetail(PaymentDto payment, List<TransactionDto> transactions) {
    }

    public record SummaryDto(String status, long count, BigDecimal amount) {
    }

    @GetMapping
    public PageResponse<PaymentDto> list(AuthenticatedUser user,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        Access.requireAnyRole(user, Roles.ADMIN, Roles.ANALYST);
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        if (status == null || status.isBlank()) {
            return PageResponse.of(payments.findAllByOrderByIdDesc(pr), PaymentDto::from);
        }
        try {
            return PageResponse.of(payments.findByStatusOrderByIdDesc(Payment.Status.valueOf(status), pr),
                    PaymentDto::from);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Trạng thái không hợp lệ: " + status);
        }
    }

    @GetMapping("/summary")
    public List<SummaryDto> summary(AuthenticatedUser user) {
        Access.requireAnyRole(user, Roles.ADMIN, Roles.ANALYST);
        return payments.summarize().stream()
                .map(s -> new SummaryDto(s.getStatus().name(), s.getCount(), s.getAmount()))
                .toList();
    }

    @GetMapping("/order/{orderId}")
    @Transactional(readOnly = true)
    public PaymentDetail byOrder(AuthenticatedUser user, @PathVariable long orderId) {
        Payment p = payments.findByOrderId(orderId)
                .orElseThrow(() -> ApiException.notFound("Đơn hàng chưa có thanh toán"));
        if (!p.getUserId().equals(user.userId()) && !user.hasAnyRole(Roles.ADMIN, Roles.ANALYST)) {
            throw ApiException.notFound("Đơn hàng chưa có thanh toán");
        }
        return new PaymentDetail(PaymentDto.from(p),
                transactions.findByPaymentIdOrderByIdAsc(p.getId()).stream().map(TransactionDto::from).toList());
    }
}
