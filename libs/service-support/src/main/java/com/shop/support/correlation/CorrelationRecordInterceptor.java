package com.shop.support.correlation;

import com.shop.common.event.Correlation;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * Trước khi listener xử lý một message: đọc header {@value Correlation#KAFKA_HEADER} và gắn vào thread
 * (Correlation + MDC) để các sự kiện phát tiếp qua Outbox mang cùng correlationId. Dọn dẹp sau khi xử lý xong.
 * Message cũ không có header: {@link com.shop.support.kafka.EventReader} lấy correlationId từ envelope.
 */
public class CorrelationRecordInterceptor implements RecordInterceptor<Object, Object> {

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        Correlation.clear();
        MDC.remove(Correlation.MDC_KEY);
        Header header = record.headers().lastHeader(Correlation.KAFKA_HEADER);
        if (header != null && header.value() != null) {
            Correlation.set(new String(header.value(), StandardCharsets.UTF_8));
            MDC.put(Correlation.MDC_KEY, Correlation.current());
        }
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        clear();
    }

    @Override
    public void clearThreadState(Consumer<?, ?> consumer) {
        clear();
    }

    private static void clear() {
        Correlation.clear();
        MDC.remove(Correlation.MDC_KEY);
    }
}
