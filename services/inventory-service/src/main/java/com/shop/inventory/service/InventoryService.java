package com.shop.inventory.service;

import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.InventoryEvents.InventoryRejected;
import com.shop.common.event.payload.InventoryEvents.InventoryReserved;
import com.shop.common.event.payload.InventoryEvents.ReservationSettled;
import com.shop.common.event.payload.InventoryEvents.ReservedLine;
import com.shop.common.event.payload.InventoryEvents.StockLow;
import com.shop.common.event.payload.InventoryEvents.StockUpdated;
import com.shop.common.event.payload.NotificationEvents.NotificationRequested;
import com.shop.common.event.payload.OrderEvents.OrderCreated;
import com.shop.common.event.payload.OrderEvents.OrderLine;
import com.shop.common.event.payload.ProductEvents.ProductChanged;
import com.shop.common.security.Roles;
import com.shop.inventory.domain.Inventory;
import com.shop.inventory.domain.InventoryRepository;
import com.shop.inventory.domain.InventoryTransaction;
import com.shop.inventory.domain.InventoryTransaction.Type;
import com.shop.inventory.domain.InventoryTransactionRepository;
import com.shop.inventory.domain.Reservation;
import com.shop.inventory.domain.ReservationRepository;
import com.shop.support.outbox.IdempotencyGuard;
import com.shop.support.outbox.OutboxService;
import com.shop.support.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    static final String CONSUMER = "inventory-service";

    private final InventoryRepository inventories;
    private final ReservationRepository reservations;
    private final InventoryTransactionRepository transactions;
    private final OutboxService outbox;
    private final IdempotencyGuard guard;
    private final int defaultInitialStock;
    private final int defaultThreshold;

    public InventoryService(InventoryRepository inventories, ReservationRepository reservations,
                            InventoryTransactionRepository transactions, OutboxService outbox, IdempotencyGuard guard,
                            @Value("${shop.inventory.default-initial-stock:100}") int defaultInitialStock,
                            @Value("${shop.inventory.default-low-stock-threshold:10}") int defaultThreshold) {
        this.inventories = inventories;
        this.reservations = reservations;
        this.transactions = transactions;
        this.outbox = outbox;
        this.guard = guard;
        this.defaultInitialStock = defaultInitialStock;
        this.defaultThreshold = defaultThreshold;
    }

    // ------------------------------------------------------------------ product-events

    @Transactional
    public void applyProductEvent(EventEnvelope env, ProductChanged p) {
        Inventory inv = inventories.findById(p.productId()).orElse(null);
        if (inv == null) {
            int stock = p.initialStock() != null ? p.initialStock() : defaultInitialStock;
            inv = inventories.save(new Inventory(p.productId(), p.name(), stock, defaultThreshold));
            transactions.save(new InventoryTransaction(p.productId(), null, Type.INIT, stock, "Khởi tạo tồn kho"));
            log.info("Khởi tạo tồn kho sản phẩm #{} = {}", p.productId(), stock);
        } else if (!inv.getProductName().equals(p.name())) {
            inv.rename(p.name());
        }
        publishStock(inv);
    }

    // ------------------------------------------------------------------ order-events

    /**
     * Giữ hàng cho đơn mới. Nếu một sản phẩm không đủ hàng, toàn bộ transaction rollback
     * (không giữ một phần) và ném {@link InsufficientStockException} để caller phát InventoryRejected.
     */
    @Transactional
    public void reserve(EventEnvelope env, OrderCreated order) {
        if (!guard.firstTime(env, CONSUMER)) {
            return;
        }
        // Gộp và sắp xếp theo productId → các transaction luôn khóa hàng theo cùng thứ tự, tránh deadlock
        Map<Long, Integer> lines = new TreeMap<>();
        for (OrderLine line : order.items()) {
            lines.merge(line.productId(), line.quantity(), Integer::sum);
        }
        Instant now = Instant.now();
        List<ReservedLine> reserved = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : lines.entrySet()) {
            long productId = e.getKey();
            int qty = e.getValue();
            if (inventories.tryReserve(productId, qty, now) == 0) {
                Inventory inv = inventories.findById(productId).orElse(null);
                String reason = inv == null
                        ? "Sản phẩm #" + productId + " chưa có thông tin tồn kho"
                        : "'" + inv.getProductName() + "' chỉ còn " + inv.getAvailable() + ", yêu cầu " + qty;
                throw new InsufficientStockException(productId, reason);
            }
            reservations.save(new Reservation(order.orderId(), productId, qty));
            transactions.save(new InventoryTransaction(productId, order.orderId(), Type.RESERVE, qty,
                    "Giữ hàng cho đơn #" + order.orderId()));
            reserved.add(new ReservedLine(productId, qty));
        }
        outbox.publish(Topics.INVENTORY_EVENTS, EventTypes.INVENTORY_RESERVED, String.valueOf(order.orderId()),
                new InventoryReserved(order.orderId(), reserved));
        for (ReservedLine line : reserved) {
            inventories.findById(line.productId()).ifPresent(inv -> {
                publishStock(inv);
                checkLowStock(inv, inv.getAvailable() + line.quantity());
            });
        }
        log.info("Đã giữ hàng cho đơn #{}: {}", order.orderId(), reserved);
    }

    /** Ghi nhận từ chối trong transaction riêng (transaction giữ hàng đã rollback). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reject(EventEnvelope env, OrderCreated order, InsufficientStockException cause) {
        if (!guard.firstTime(env, CONSUMER)) {
            return;
        }
        outbox.publish(Topics.INVENTORY_EVENTS, EventTypes.INVENTORY_REJECTED, String.valueOf(order.orderId()),
                new InventoryRejected(order.orderId(), cause.getProductId(), cause.getMessage()));
        log.info("Từ chối đơn #{}: {}", order.orderId(), cause.getMessage());
    }

    /** Đơn đã thanh toán → xuất kho phần đang giữ. */
    @Transactional
    public void commit(EventEnvelope env, long orderId) {
        if (!guard.firstTime(env, CONSUMER)) {
            return;
        }
        Instant now = Instant.now();
        List<ReservedLine> settled = new ArrayList<>();
        for (Reservation r : reservations.findByOrderIdOrderByProductIdAsc(orderId)) {
            if (r.getStatus() != Reservation.Status.RESERVED) {
                continue;
            }
            inventories.commitReserved(r.getProductId(), r.getQuantity(), now);
            r.mark(Reservation.Status.COMMITTED);
            transactions.save(new InventoryTransaction(r.getProductId(), orderId, Type.COMMIT, r.getQuantity(),
                    "Xuất kho cho đơn #" + orderId));
            settled.add(new ReservedLine(r.getProductId(), r.getQuantity()));
        }
        if (!settled.isEmpty()) {
            outbox.publish(Topics.INVENTORY_EVENTS, EventTypes.INVENTORY_COMMITTED, String.valueOf(orderId),
                    new ReservationSettled(orderId, settled));
            settled.forEach(l -> inventories.findById(l.productId()).ifPresent(this::publishStock));
        }
    }

    /** Hành động bù: đơn bị hủy → trả hàng về kho (dù đang giữ hay đã xuất kho). */
    @Transactional
    public void release(EventEnvelope env, long orderId) {
        if (!guard.firstTime(env, CONSUMER)) {
            return;
        }
        Instant now = Instant.now();
        List<ReservedLine> released = new ArrayList<>();
        for (Reservation r : reservations.findByOrderIdOrderByProductIdAsc(orderId)) {
            switch (r.getStatus()) {
                case RESERVED -> {
                    inventories.releaseReserved(r.getProductId(), r.getQuantity(), now);
                    transactions.save(new InventoryTransaction(r.getProductId(), orderId, Type.RELEASE,
                            r.getQuantity(), "Hoàn hàng giữ của đơn #" + orderId));
                }
                case COMMITTED -> {
                    inventories.addAvailable(r.getProductId(), r.getQuantity(), now);
                    transactions.save(new InventoryTransaction(r.getProductId(), orderId, Type.RETURN,
                            r.getQuantity(), "Nhập lại hàng của đơn bị hủy #" + orderId));
                }
                case RELEASED -> {
                    continue;
                }
            }
            r.mark(Reservation.Status.RELEASED);
            released.add(new ReservedLine(r.getProductId(), r.getQuantity()));
        }
        if (!released.isEmpty()) {
            outbox.publish(Topics.INVENTORY_EVENTS, EventTypes.INVENTORY_RELEASED, String.valueOf(orderId),
                    new ReservationSettled(orderId, released));
            released.forEach(l -> inventories.findById(l.productId()).ifPresent(this::publishStock));
            log.info("Đã hoàn kho cho đơn #{}: {}", orderId, released);
        }
    }

    // ------------------------------------------------------------------ Quản lý kho (REST)

    @Transactional
    public Inventory restock(long productId, int quantity, String note, String actor) {
        if (quantity <= 0) {
            throw ApiException.badRequest("Số lượng nhập phải lớn hơn 0");
        }
        if (inventories.addAvailable(productId, quantity, Instant.now()) == 0) {
            throw ApiException.notFound("Không tìm thấy tồn kho sản phẩm #" + productId);
        }
        transactions.save(new InventoryTransaction(productId, null, Type.RESTOCK, quantity,
                (note == null || note.isBlank() ? "Nhập kho" : note) + " (" + actor + ")"));
        Inventory inv = inventories.findById(productId).orElseThrow();
        publishStock(inv);
        return inv;
    }

    @Transactional
    public Inventory changeThreshold(long productId, int threshold) {
        if (threshold < 0) {
            throw ApiException.badRequest("Ngưỡng cảnh báo không hợp lệ");
        }
        Inventory inv = inventories.findById(productId)
                .orElseThrow(() -> ApiException.notFound("Không tìm thấy tồn kho sản phẩm #" + productId));
        inv.changeThreshold(threshold);
        publishStock(inv);
        checkLowStock(inv, Integer.MAX_VALUE);
        return inv;
    }

    // ------------------------------------------------------------------ Nội bộ

    private void publishStock(Inventory inv) {
        outbox.publish(Topics.INVENTORY_EVENTS, EventTypes.STOCK_UPDATED, String.valueOf(inv.getProductId()),
                new StockUpdated(inv.getProductId(), inv.getProductName(), inv.getAvailable(), inv.getReserved(),
                        inv.getLowStockThreshold()));
    }

    /** Phát cảnh báo khi tồn kho vừa vượt xuống dưới ngưỡng (không spam mỗi lần bán). */
    private void checkLowStock(Inventory inv, int previousAvailable) {
        if (inv.isLowStock() && previousAvailable > inv.getLowStockThreshold()) {
            outbox.publish(Topics.INVENTORY_EVENTS, EventTypes.STOCK_LOW, String.valueOf(inv.getProductId()),
                    new StockLow(inv.getProductId(), inv.getProductName(), inv.getAvailable(), inv.getLowStockThreshold()));
            outbox.publish(Topics.NOTIFICATION_EVENTS, EventTypes.NOTIFICATION_REQUESTED, "role-warehouse",
                    new NotificationRequested(null, Roles.WAREHOUSE_MANAGER, "STOCK_LOW",
                            "Tồn kho thấp: " + inv.getProductName(),
                            "Chỉ còn " + inv.getAvailable() + " sản phẩm (ngưỡng " + inv.getLowStockThreshold() + ")",
                            "product:" + inv.getProductId()));
            log.warn("Tồn kho thấp: #{} {} còn {}", inv.getProductId(), inv.getProductName(), inv.getAvailable());
        }
    }
}
