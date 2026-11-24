package com.shop.promotion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Số lượt một tài khoản đã dùng một ưu đãi: scope "voucher:{id}" (số lần dùng mã) hoặc "flash:{itemId}" (số sản phẩm
 * mua giá flash sale). Chỉ thay đổi qua UPDATE có điều kiện trong {@code PromotionLedger} → giới hạn mỗi tài khoản
 * đúng cả khi một người đặt nhiều đơn cùng lúc.
 */
@Entity
@Table(name = "usage_counters",
        uniqueConstraints = @UniqueConstraint(name = "uk_usage_scope_user", columnNames = {"scope", "userId"}))
public class UsageCounter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 48)
    private String scope;

    @Column(nullable = false)
    private Long userId;

    private int used;

    protected UsageCounter() {
    }

    public String getScope() {
        return scope;
    }

    public Long getUserId() {
        return userId;
    }

    public int getUsed() {
        return used;
    }
}
