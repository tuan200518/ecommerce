package com.shop.promotion.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Các thao tác giữ/hoàn suất và lượt dùng – mỗi thao tác là MỘT câu UPDATE có điều kiện nên nguyên tử ở mức CSDL:
 * nhiều đơn tranh nhau suất flash sale cuối cùng thì chỉ đúng số đơn bằng số suất thành công (giống cách Inventory
 * giữ hàng). SQL chạy được trên cả PostgreSQL và H2 (MODE=PostgreSQL) dùng trong test.
 */
@Component
public class PromotionLedger {

    private final JdbcTemplate jdbc;

    public PromotionLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean reserveFlash(long itemId, int quantity) {
        return jdbc.update("UPDATE flash_sale_items SET sold = sold + ? WHERE id = ? AND sold + ? <= quota",
                quantity, itemId, quantity) == 1;
    }

    public void releaseFlash(long itemId, int quantity) {
        jdbc.update("UPDATE flash_sale_items SET sold = GREATEST(sold - ?, 0) WHERE id = ?", quantity, itemId);
    }

    public boolean useVoucher(long voucherId) {
        return jdbc.update("""
                UPDATE vouchers SET used = used + 1
                WHERE id = ? AND active = TRUE AND (usage_limit IS NULL OR used < usage_limit)""", voucherId) == 1;
    }

    public void releaseVoucher(long voucherId) {
        jdbc.update("UPDATE vouchers SET used = GREATEST(used - 1, 0) WHERE id = ?", voucherId);
    }

    /** Tăng bộ đếm của tài khoản nếu không vượt {@code limit}. */
    public boolean incrementUsage(String scope, long userId, int quantity, int limit) {
        jdbc.update("INSERT INTO usage_counters (scope, user_id, used) VALUES (?, ?, 0) ON CONFLICT DO NOTHING",
                scope, userId);
        return jdbc.update("UPDATE usage_counters SET used = used + ? WHERE scope = ? AND user_id = ? AND used + ? <= ?",
                quantity, scope, userId, quantity, limit) == 1;
    }

    public void decrementUsage(String scope, long userId, int quantity) {
        jdbc.update("UPDATE usage_counters SET used = GREATEST(used - ?, 0) WHERE scope = ? AND user_id = ?",
                quantity, scope, userId);
    }

    public int usage(String scope, long userId) {
        List<Integer> rows = jdbc.queryForList("SELECT used FROM usage_counters WHERE scope = ? AND user_id = ?",
                Integer.class, scope, userId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    static String flashScope(long itemId) {
        return "flash:" + itemId;
    }

    static String voucherScope(long voucherId) {
        return "voucher:" + voucherId;
    }
}
