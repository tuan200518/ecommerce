package com.shop.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "user_profiles")
public class UserProfile {

    /** Trùng với id tài khoản bên Auth Service. */
    @Id
    private Long userId;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false, length = 150)
    private String fullName;

    @Column(length = 20)
    private String phone;

    private LocalDate dateOfBirth;

    @Column(length = 1000)
    private String avatarUrl;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    protected UserProfile() {
    }

    public UserProfile(long userId, String email, String fullName) {
        this.userId = userId;
        this.email = email;
        this.fullName = fullName;
        this.createdAt = Instant.now();
    }

    public void update(String fullName, String phone, LocalDate dateOfBirth, String avatarUrl) {
        this.fullName = fullName;
        this.phone = phone;
        this.dateOfBirth = dateOfBirth;
        this.avatarUrl = avatarUrl;
        this.updatedAt = Instant.now();
    }

    public Long getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPhone() {
        return phone;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
