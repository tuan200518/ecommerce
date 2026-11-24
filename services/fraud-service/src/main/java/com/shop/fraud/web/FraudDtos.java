package com.shop.fraud.web;

import com.shop.common.event.payload.FraudEvents.RiskSignal;
import com.shop.fraud.service.RiskRules.RuleInfo;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class FraudDtos {

    private FraudDtos() {
    }

    public record RiskCaseDto(long orderId, long userId, BigDecimal totalAmount, int score, String level,
                              String status, List<RiskSignal> signals, String reviewer, String reviewNote,
                              Instant createdAt, Instant decidedAt) {
    }

    public record ReviewRequest(@NotNull Boolean approved, @Size(max = 500) String note) {
    }

    public record SummaryDto(Map<String, Long> byStatus, Map<String, Long> byLevel, List<RuleInfo> rules,
                             int reviewScore, int minSignalsForReview) {
    }
}
