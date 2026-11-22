package com.shop.common.event.payload;

import java.util.Set;

/** Payload cho topic user-events. */
public final class UserEvents {

    public record UserRegistered(long userId, String email, String fullName, Set<String> roles) {
    }

    public record UserLoggedIn(long userId, String email) {
    }

    public record UserRolesChanged(long userId, String email, Set<String> roles) {
    }

    private UserEvents() {
    }
}
