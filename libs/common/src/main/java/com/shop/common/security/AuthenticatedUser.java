package com.shop.common.security;

import java.util.Set;

public record AuthenticatedUser(long userId, String email, Set<String> roles) {

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasAnyRole(String... candidates) {
        for (String r : candidates) {
            if (roles.contains(r)) {
                return true;
            }
        }
        return false;
    }
}
