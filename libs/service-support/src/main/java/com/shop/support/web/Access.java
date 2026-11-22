package com.shop.support.web;

import com.shop.common.security.AuthenticatedUser;

/** Kiểm tra phân quyền ở tầng service (bổ sung cho kiểm tra ở API Gateway). */
public final class Access {

    private Access() {
    }

    public static void requireAnyRole(AuthenticatedUser user, String... roles) {
        if (!user.hasAnyRole(roles)) {
            throw ApiException.forbidden("Bạn không có quyền thực hiện thao tác này");
        }
    }
}
