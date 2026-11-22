package com.shop.common.security;

/**
 * Header nội bộ do API Gateway gắn vào request sau khi xác thực JWT.
 * Gateway luôn xóa các header này từ client trước khi gắn lại, nên service phía sau có thể tin cậy.
 */
public final class AuthHeaders {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_EMAIL = "X-User-Email";
    public static final String USER_ROLES = "X-User-Roles";

    private AuthHeaders() {
    }
}
