package com.shop.common.security;

import java.util.Set;

/** Các vai trò trong hệ thống (mục 1.3 planning). */
public final class Roles {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String ADMIN = "ADMIN";
    public static final String PRODUCT_MANAGER = "PRODUCT_MANAGER";
    public static final String WAREHOUSE_MANAGER = "WAREHOUSE_MANAGER";
    public static final String ANALYST = "ANALYST";

    public static final Set<String> ALL = Set.of(CUSTOMER, ADMIN, PRODUCT_MANAGER, WAREHOUSE_MANAGER, ANALYST);

    private Roles() {
    }
}
