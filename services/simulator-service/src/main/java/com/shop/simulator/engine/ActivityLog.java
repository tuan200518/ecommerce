package com.shop.simulator.engine;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Bộ đếm thống kê + nhật ký hoạt động gần nhất của khách/nhân viên ảo. */
public class ActivityLog {

    public enum Kind {
        SESSION, BROWSE_ONLY, ABANDON_CART, ORDER, CANCEL, REGISTER, RESTOCK, PACK, SHIP, DELIVER, BURST,
        SUSPICIOUS, RISK_REVIEW, REVIEW, QUESTION, FLASH_SALE, VOUCHER, REDEEM, ERROR
    }

    public record Entry(Instant at, Kind kind, String actor, String message) {
    }

    private static final int MAX_ENTRIES = 80;

    private final Map<Kind, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Deque<Entry> recent = new ArrayDeque<>();
    private final AtomicLong orderValue = new AtomicLong();

    public void record(Kind kind, String actor, String message) {
        counters.computeIfAbsent(kind, k -> new AtomicLong()).incrementAndGet();
        if (kind == Kind.SESSION) {
            return; // không ghi nhật ký cho từng phiên để danh sách gọn
        }
        synchronized (recent) {
            recent.addFirst(new Entry(Instant.now(), kind, actor, message));
            while (recent.size() > MAX_ENTRIES) {
                recent.removeLast();
            }
        }
    }

    public void addOrderValue(double amount) {
        orderValue.addAndGet(Math.round(amount));
    }

    public long count(Kind kind) {
        AtomicLong c = counters.get(kind);
        return c == null ? 0 : c.get();
    }

    public long orderValue() {
        return orderValue.get();
    }

    public List<Entry> recent(int limit) {
        synchronized (recent) {
            return recent.stream().limit(limit).toList();
        }
    }

    public void reset() {
        counters.clear();
        orderValue.set(0);
        synchronized (recent) {
            recent.clear();
        }
    }
}
