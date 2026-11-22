package com.shop.payment.service;

import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.NotificationEvents.NotificationRequested;
import com.shop.common.event.payload.OrderEvents.PaymentRequested;
import com.shop.common.event.payload.PaymentEvents.PaymentResult;
import com.shop.payment.domain.Payment;
import com.shop.payment.domain.PaymentRepository;
import com.shop.payment.domain.PaymentTransactionRepository;
import com.shop.payment.domain.PaymentTransaction;
import com.shop.payment.service.MockPaymentGateway.GatewayResult;
import com.shop.support.outbox.IdempotencyGuard;
import com.shop.support.outbox.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    static final String CONSUMER = "payment-service";

    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;
    private final MockPaymentGateway gateway;
    private final OutboxService outbox;
    private final IdempotencyGuard guard;

    public PaymentService(PaymentRepository payments, PaymentTransactionRepository transactions,
                          MockPaymentGateway gateway, OutboxService outbox, IdempotencyGuard guard) {
        this.payments = payments;
        this.transactions = transactions;
        this.gateway = gateway;
        this.outbox = outbox;
        this.guard = guard;
    }

    /** Nhận PaymentRequested từ Order Service → thu tiền mô phỏng → phát PaymentCompleted/PaymentFailed. */
    @Transactional
    public void process(EventEnvelope env, PaymentRequested req) {
        if (!guard.firstTime(env, CONSUMER)) {
            return;
        }
        if (payments.findByOrderId(req.orderId()).isPresent()) {
            log.info("Đơn #{} đã có payment, bỏ qua yêu cầu trùng", req.orderId());
            return;
        }
        Payment payment = payments.save(new Payment(req.orderId(), req.userId(), req.amount(), req.paymentMethod()));
        GatewayResult result = gateway.charge(req.paymentMethod(), req.amount());
        transactions.save(new PaymentTransaction(payment.getId(), PaymentTransaction.Type.CHARGE, result.success(),
                req.amount(), result.reference(), result.message()));
        if (result.success()) {
            payment.succeed();
            publish(EventTypes.PAYMENT_COMPLETED, payment, null);
        } else {
            payment.fail(result.message());
            publish(EventTypes.PAYMENT_FAILED, payment, result.message());
        }
        log.info("Thanh toán đơn #{} {}: {}", req.orderId(), payment.getStatus(), result.message());
    }

    /** Hành động bù: đơn bị hủy sau khi đã thu tiền → hoàn tiền. */
    @Transactional
    public void refundIfPaid(EventEnvelope env, long orderId, String reason) {
        if (!guard.firstTime(env, CONSUMER)) {
            return;
        }
        payments.findByOrderId(orderId)
                .filter(p -> p.getStatus() == Payment.Status.SUCCEEDED)
                .ifPresent(payment -> {
                    GatewayResult result = gateway.refund(payment.getAmount());
                    transactions.save(new PaymentTransaction(payment.getId(), PaymentTransaction.Type.REFUND, true,
                            payment.getAmount(), result.reference(), reason));
                    payment.refund();
                    publish(EventTypes.PAYMENT_REFUNDED, payment, reason);
                    outbox.publish(Topics.NOTIFICATION_EVENTS, EventTypes.NOTIFICATION_REQUESTED,
                            "user-" + payment.getUserId(),
                            new NotificationRequested(payment.getUserId(), null, "PAYMENT_REFUNDED",
                                    "Đã hoàn tiền đơn #" + orderId,
                                    "Số tiền " + payment.getAmount().toPlainString() + " ₫ sẽ được hoàn về tài khoản của bạn.",
                                    "order:" + orderId));
                    log.info("Hoàn tiền đơn #{}", orderId);
                });
    }

    private void publish(String type, Payment p, String reason) {
        outbox.publish(Topics.PAYMENT_EVENTS, type, String.valueOf(p.getOrderId()),
                new PaymentResult(p.getId(), p.getOrderId(), p.getUserId(), p.getAmount(), p.getMethod(),
                        p.getStatus().name(), reason));
    }
}
