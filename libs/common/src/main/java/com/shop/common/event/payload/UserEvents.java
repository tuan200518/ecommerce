package com.shop.common.event.payload;

import java.util.Set;

/** Payload cho topic user-events. */
public final class UserEvents {

    /** @param referralCode mã giới thiệu khách nhập khi đăng ký (có thể null) – Loyalty Service xử lý thưởng. */
    public record UserRegistered(long userId, String email, String fullName, Set<String> roles, String referralCode) {

        public UserRegistered(long userId, String email, String fullName, Set<String> roles) {
            this(userId, email, fullName, roles, null);
        }
    }

    public record UserLoggedIn(long userId, String email) {
    }

    public record UserRolesChanged(long userId, String email, Set<String> roles) {
    }

    private UserEvents() {
    }
}
