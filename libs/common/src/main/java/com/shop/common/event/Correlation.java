package com.shop.common.event;

import java.util.UUID;

/**
 * correlationId của luồng xử lý hiện tại (lưu theo thread).
 * <p>
 * Một yêu cầu của khách (REST qua Gateway) sinh ra một correlationId; mọi sự kiện phát ra trong quá trình xử lý
 * yêu cầu đó — và các sự kiện tiếp theo do consumer phát ra khi xử lý chúng — đều mang cùng giá trị này.
 * Nhờ vậy có thể truy vết trọn một luồng Saga (OrderCreated → InventoryReserved → PaymentCompleted → ...)
 * trong log và trên Kafka UI.
 * <ul>
 *   <li>HTTP: header {@link #HTTP_HEADER} (Gateway tự tạo nếu client không gửi).</li>
 *   <li>Kafka: header {@link #KAFKA_HEADER} + trường {@code correlationId} trong {@link EventEnvelope}.</li>
 *   <li>Log: khóa MDC {@link #MDC_KEY}.</li>
 * </ul>
 */
public final class Correlation {

    public static final String HTTP_HEADER = "X-Correlation-Id";
    public static final String KAFKA_HEADER = "correlationId";
    public static final String MDC_KEY = "correlationId";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private Correlation() {
    }

    /** correlationId đang gắn với thread, hoặc null nếu không có. */
    public static String current() {
        return CURRENT.get();
    }

    /** correlationId đang gắn với thread; nếu chưa có thì tạo mới (bắt đầu một luồng truy vết). */
    public static String currentOrNew() {
        String id = CURRENT.get();
        return id != null ? id : newId();
    }

    public static void set(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(sanitize(correlationId));
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Giới hạn độ dài và ký tự để giá trị từ client không làm hỏng log/header. */
    public static String sanitize(String raw) {
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9._:-]", "");
        if (cleaned.isEmpty()) {
            return newId();
        }
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }
}
