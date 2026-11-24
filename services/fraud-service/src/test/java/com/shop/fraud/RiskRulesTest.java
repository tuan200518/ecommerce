package com.shop.fraud;

import com.shop.common.event.payload.FraudEvents.RiskSignal;
import com.shop.fraud.service.RiskRules;
import com.shop.fraud.service.RiskRules.Input;
import com.shop.fraud.service.RiskRules.Level;
import com.shop.fraud.service.RiskRules.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RiskRulesTest {

    private final RiskRules rules = new RiskRules(FraudProperties.defaults());

    private static Input normal() {
        return new Input(new BigDecimal("1500000"), 2, 1, 0, 0, 0, Duration.ofDays(30), 0);
    }

    @Test
    void ordinaryOrder_isLowRisk() {
        Result r = rules.evaluate(normal());
        assertThat(r.score()).isZero();
        assertThat(r.level()).isEqualTo(Level.LOW);
        assertThat(r.requiresReview()).isFalse();
    }

    @Test
    void singleStrongSignal_neverTriggersReviewOnItsOwn() {
        // Đơn rất lớn (50 điểm) nhưng chỉ một tín hiệu → không đủ để đưa vào xác minh
        Result r = rules.evaluate(new Input(new BigDecimal("150000000"), 5, 2, 0, 0, 0, Duration.ofDays(400), 0));
        assertThat(r.signals()).extracting(RiskSignal::rule).containsExactly("VERY_HIGH_VALUE");
        assertThat(r.level()).isEqualTo(Level.MEDIUM);
        assertThat(r.requiresReview()).isFalse();
    }

    @Test
    void newAccountBuyingExpensiveGoodsInBulk_requiresReview() {
        Result r = rules.evaluate(new Input(new BigDecimal("120000000"), 32, 12, 0, 0, 0, Duration.ofMinutes(5), 0));
        assertThat(r.signals()).extracting(RiskSignal::rule)
                .containsExactlyInAnyOrder("VERY_HIGH_VALUE", "NEW_ACCOUNT", "BULK_QUANTITY");
        assertThat(r.score()).isEqualTo(95);
        assertThat(r.level()).isEqualTo(Level.HIGH);
        assertThat(r.requiresReview()).isTrue();
        assertThat(r.signals()).allSatisfy(s -> assertThat(s.detail()).isNotBlank()); // giải thích được
    }

    @Test
    void behaviouralSignals_accumulate_andScoreIsCappedAt100() {
        Result r = rules.evaluate(new Input(new BigDecimal("200000000"), 40, 20, 6, 4, 3, Duration.ofMinutes(1), 3));
        assertThat(r.signals()).hasSize(7);
        assertThat(r.score()).isEqualTo(100);
        assertThat(r.requiresReview()).isTrue();
    }

    @Test
    void unknownAccountAge_skipsNewAccountRule() {
        Result r = rules.evaluate(new Input(new BigDecimal("20000000"), 1, 1, 0, 0, 0, null, 0));
        assertThat(r.signals()).isEmpty();
    }

    @Test
    void everyRuleIsDescribedForReviewers() {
        assertThat(rules.describe()).hasSize(8).allSatisfy(info -> assertThat(info.points()).isPositive());
    }
}
