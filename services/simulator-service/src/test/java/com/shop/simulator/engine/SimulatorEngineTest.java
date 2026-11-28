package com.shop.simulator.engine;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatorEngineTest {

    @Test
    void popularProductsAreBoughtMuchMoreOften_butEveryProductCanBePicked() {
        List<Catalog.Item> items = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> new Catalog.Item(i, "SKU-" + i, "P" + i, 100_000, null)).toList();
        Catalog catalog = Catalog.of(items);
        Map<Long, Integer> hits = new HashMap<>();
        for (int i = 0; i < 50_000; i++) {
            hits.merge(catalog.pickPopular().id(), 1, Integer::sum);
        }
        long top = catalog.items().get(0).id();
        long last = catalog.items().get(19).id();
        assertThat(hits).hasSize(20);
        assertThat(hits.get(top)).isGreaterThan(hits.get(last) * 5);
    }

    @Test
    void invalidSettingsAreClampedToSafeRanges() {
        SimulatorSettings s = new SimulatorSettings(10_000, 0, -1, 2, Double.NaN, 0.5, 0.1, true, true, false, 3, -0.5, true, 7, -1, 2, true, 1.5)
                .validated();
        assertThat(s.sessionsPerMinute()).isEqualTo(600);
        assertThat(s.maxConcurrentSessions()).isEqualTo(1);
        assertThat(s.browseOnlyRate()).isZero();
        assertThat(s.cartAbandonRate()).isEqualTo(1);
        assertThat(s.paymentFailureRate()).isZero();
        assertThat(s.cancelRate()).isEqualTo(0.5);
        assertThat(s.suspiciousRate()).isEqualTo(1);
        assertThat(s.reviewRate()).isZero();
        assertThat(s.voucherRate()).isEqualTo(1);
        assertThat(s.flashSaleRate()).isZero();
        assertThat(s.supportRate()).isEqualTo(1);
        assertThat(s.recommendationRate()).isEqualTo(1);
    }

    @Test
    void personasProduceBasketsInTheirRange() {
        for (int i = 0; i < 1000; i++) {
            assertThat(VirtualCustomer.Persona.THRIFTY.lines()).isEqualTo(1);
            assertThat(VirtualCustomer.Persona.BIG_SPENDER.lines()).isBetween(2, 4);
            assertThat(VirtualCustomer.Persona.REGULAR.quantity()).isBetween(1, 2);
        }
    }
}
