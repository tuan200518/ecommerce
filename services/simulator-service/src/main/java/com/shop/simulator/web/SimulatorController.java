package com.shop.simulator.web;

import com.shop.common.security.AuthHeaders;
import com.shop.common.security.Roles;
import com.shop.simulator.engine.CustomerSimulator;
import com.shop.simulator.engine.CustomerSimulator.Status;
import com.shop.simulator.engine.SimulatorSettings;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;

/** Bảng điều khiển khách hàng ảo (chỉ ADMIN, được API Gateway kiểm tra trước). */
@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {

    private final CustomerSimulator simulator;

    public SimulatorController(CustomerSimulator simulator) {
        this.simulator = simulator;
    }

    public record BurstRequest(int orders, int seconds) {
    }

    @GetMapping
    public Status status(@RequestHeader(value = AuthHeaders.USER_ROLES, required = false) String roles) {
        requireAdmin(roles);
        return simulator.status();
    }

    @PostMapping("/start")
    public Status start(@RequestHeader(value = AuthHeaders.USER_ROLES, required = false) String roles) {
        requireAdmin(roles);
        simulator.start();
        return simulator.status();
    }

    @PostMapping("/stop")
    public Status stop(@RequestHeader(value = AuthHeaders.USER_ROLES, required = false) String roles) {
        requireAdmin(roles);
        simulator.stop();
        return simulator.status();
    }

    @PutMapping("/settings")
    public Status settings(@RequestHeader(value = AuthHeaders.USER_ROLES, required = false) String roles,
                           @RequestBody SimulatorSettings settings) {
        requireAdmin(roles);
        simulator.update(settings);
        return simulator.status();
    }

    @PostMapping("/burst")
    public Status burst(@RequestHeader(value = AuthHeaders.USER_ROLES, required = false) String roles,
                        @RequestBody BurstRequest req) {
        requireAdmin(roles);
        simulator.burst(req.orders(), req.seconds());
        return simulator.status();
    }

    @PostMapping("/reset-stats")
    public Status reset(@RequestHeader(value = AuthHeaders.USER_ROLES, required = false) String roles) {
        requireAdmin(roles);
        simulator.resetStats();
        return simulator.status();
    }

    private static void requireAdmin(String roles) {
        if (roles == null || Arrays.stream(roles.split(",")).map(String::trim).noneMatch(Roles.ADMIN::equals)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ ADMIN được điều khiển khách hàng ảo");
        }
    }
}
