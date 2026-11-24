package com.shop.auth.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 150)
    private String fullName;

    @Column(nullable = false)
    private boolean enabled = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", length = 32)
    private Set<String> roles = new LinkedHashSet<>();

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastLoginAt;

    // Chống dò mật khẩu (thêm ở phiên bản 2.1 – cho phép NULL để ddl-auto=update thêm vào bảng đã có dữ liệu)
    /** Số lần sai mật khẩu liên tiếp kể từ lần đăng nhập thành công gần nhất. */
    private Integer failedLoginAttempts;

    /** Tạm khóa đăng nhập tới thời điểm này. */
    private Instant lockedUntil;

    protected UserAccount() {
    }

    public UserAccount(String email, String passwordHash, String fullName, Set<String> roles) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.roles.addAll(roles);
        this.createdAt = Instant.now();
    }

    public void setRoles(Set<String> roles) {
        this.roles.clear();
        this.roles.addAll(roles);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void recordLogin() {
        this.lastLoginAt = Instant.now();
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    /** Đang trong thời gian tạm khóa do đăng nhập sai nhiều lần. */
    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /**
     * Ghi nhận một lần sai mật khẩu; đủ {@code maxAttempts} lần liên tiếp → tạm khóa {@code lockDuration}.
     *
     * @return true nếu lần sai này làm tài khoản bị khóa
     */
    public boolean registerFailedLogin(int maxAttempts, Duration lockDuration, Instant now) {
        int attempts = (failedLoginAttempts == null ? 0 : failedLoginAttempts) + 1;
        if (attempts >= maxAttempts) {
            lockedUntil = now.plus(lockDuration);
            failedLoginAttempts = 0;
            return true;
        }
        failedLoginAttempts = attempts;
        return false;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts == null ? 0 : failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
