package com.shop.simulator;

import com.shop.simulator.engine.CustomerSimulator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "shop.simulator.autostart=false")
class SimulatorContextTest {

    @Autowired
    CustomerSimulator simulator;

    @Test
    void startsStoppedWithSeededCustomers() {
        var status = simulator.status();
        assertThat(status.running()).isFalse();
        assertThat(status.customers()).isEqualTo(51); // customer@ + customer1..50
        assertThat(status.settings().sessionsPerMinute()).isEqualTo(20);
    }
}
