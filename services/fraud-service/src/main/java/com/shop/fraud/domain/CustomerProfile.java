package com.shop.fraud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Thời điểm đăng ký tài khoản (từ UserRegistered) để nhận diện tài khoản mới. */
@Entity
@Table(name = "customer_profiles")
public class CustomerProfile {

    @Id
    private Long userId;

    @Column(nullable = false)
    private Instant registeredAt;

    protected CustomerProfile() {
    }

    public CustomerProfile(long userId, Instant registeredAt) {
        this.userId = userId;
        this.registeredAt = registeredAt;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }
}
