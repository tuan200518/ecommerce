package com.shop.product.service;

import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.ProductEvents.ProductChanged;
import com.shop.product.domain.Category;
import com.shop.product.domain.Product;
import com.shop.product.domain.ProductRepository;
import com.shop.support.outbox.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phát lại trạng thái hiện tại của toàn bộ danh mục lên product-events (ProductUpdated / ProductDiscontinued)
 * khi khởi động và định kỳ.
 * <p>
 * Kafka chỉ giữ message trong thời hạn retention (mặc định 7 ngày) nên sự kiện ProductCreated lúc seed dữ liệu
 * sẽ mất; service mới triển khai sau đó (vd. recommendation-service) đọc từ đầu topic sẽ không thấy sản phẩm nào.
 * Mọi consumer của product-events áp dụng sự kiện như upsert trạng thái mới nhất nên phát lại là vô hại.
 */
@Component
@ConditionalOnProperty(prefix = "shop.catalog-republish", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CatalogRepublisher {

    private static final Logger log = LoggerFactory.getLogger(CatalogRepublisher.class);
    private static final int BATCH = 200;

    private final ProductRepository products;
    private final OutboxService outbox;
    private final TransactionTemplate tx;

    public CatalogRepublisher(ProductRepository products, OutboxService outbox, TransactionTemplate tx) {
        this.products = products;
        this.outbox = outbox;
        this.tx = tx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        republish();
    }

    @Scheduled(fixedDelayString = "${shop.catalog-republish.interval:PT24H}",
            initialDelayString = "${shop.catalog-republish.interval:PT24H}")
    public void periodically() {
        republish();
    }

    /** @return số sản phẩm đã phát lại */
    public int republish() {
        int total = 0;
        int page = 0;
        try {
            while (true) {
                int current = page;
                Integer n = tx.execute(status -> {
                    Page<Product> batch = products.findAll(PageRequest.of(current, BATCH, Sort.by("id")));
                    batch.forEach(this::publish);
                    return batch.getNumberOfElements();
                });
                total += n == null ? 0 : n;
                if (n == null || n < BATCH) {
                    break;
                }
                page++;
            }
            log.info("Đã phát lại {} sản phẩm lên {} (đồng bộ read-model của các service)", total, Topics.PRODUCT_EVENTS);
        } catch (Exception e) {
            log.warn("Không phát lại được danh mục sản phẩm: {}", e.getMessage());
        }
        return total;
    }

    private void publish(Product p) {
        Category c = p.getCategory();
        outbox.publish(Topics.PRODUCT_EVENTS, p.isActive() ? EventTypes.PRODUCT_UPDATED : EventTypes.PRODUCT_DISCONTINUED,
                String.valueOf(p.getId()),
                new ProductChanged(p.getId(), p.getSku(), p.getName(), p.getPrice(),
                        c == null ? null : c.getId(), c == null ? null : c.getName(), p.getImageUrl(),
                        p.isActive(), null));
    }
}
