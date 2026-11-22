package com.shop.cart.service;

import com.shop.cart.domain.Cart;
import com.shop.cart.domain.CartItem;
import com.shop.cart.domain.CartRepository;
import com.shop.cart.domain.CatalogItem;
import com.shop.cart.domain.CatalogItemRepository;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.CartEvents.CartCleared;
import com.shop.common.event.payload.CartEvents.CartItemChanged;
import com.shop.common.event.payload.OrderEvents.OrderCreated;
import com.shop.common.event.payload.ProductEvents.ProductChanged;
import com.shop.support.outbox.IdempotencyGuard;
import com.shop.support.outbox.OutboxService;
import com.shop.support.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CartService {

    static final String CONSUMER = "cart-service";
    private static final int MAX_QTY = 20;

    public record CartLine(long productId, String name, String imageUrl, BigDecimal unitPrice, int quantity,
                           BigDecimal lineTotal, boolean available) {
    }

    public record CartView(long userId, List<CartLine> items, int totalQuantity, BigDecimal totalAmount) {
    }

    private final CartRepository carts;
    private final CatalogItemRepository catalog;
    private final OutboxService outbox;
    private final IdempotencyGuard guard;

    public CartService(CartRepository carts, CatalogItemRepository catalog, OutboxService outbox,
                       IdempotencyGuard guard) {
        this.carts = carts;
        this.catalog = catalog;
        this.outbox = outbox;
        this.guard = guard;
    }

    @Transactional(readOnly = true)
    public CartView view(long userId) {
        return carts.findByUserId(userId).map(this::toView)
                .orElse(new CartView(userId, List.of(), 0, BigDecimal.ZERO));
    }

    @Transactional
    public CartView add(long userId, long productId, int quantity) {
        CatalogItem product = catalog.findById(productId)
                .filter(CatalogItem::isActive)
                .orElseThrow(() -> ApiException.badRequest("Sản phẩm không tồn tại hoặc đã ngừng bán"));
        Cart cart = carts.findByUserId(userId).orElseGet(() -> carts.save(new Cart(userId)));
        int newQty = cart.find(productId).map(CartItem::getQuantity).orElse(0) + quantity;
        if (newQty > MAX_QTY) {
            throw ApiException.badRequest("Mỗi sản phẩm tối đa " + MAX_QTY + " trong giỏ");
        }
        cart.add(productId, quantity);
        publish(EventTypes.CART_ITEM_ADDED, userId, product, quantity);
        return toView(cart);
    }

    @Transactional
    public CartView update(long userId, long productId, int quantity) {
        if (quantity <= 0) {
            return remove(userId, productId);
        }
        if (quantity > MAX_QTY) {
            throw ApiException.badRequest("Mỗi sản phẩm tối đa " + MAX_QTY + " trong giỏ");
        }
        Cart cart = carts.findByUserId(userId).orElseThrow(() -> ApiException.notFound("Giỏ hàng trống"));
        if (!cart.setQuantity(productId, quantity)) {
            throw ApiException.notFound("Sản phẩm không có trong giỏ");
        }
        catalog.findById(productId).ifPresent(p -> publish(EventTypes.CART_ITEM_UPDATED, userId, p, quantity));
        return toView(cart);
    }

    @Transactional
    public CartView remove(long userId, long productId) {
        Cart cart = carts.findByUserId(userId).orElseThrow(() -> ApiException.notFound("Giỏ hàng trống"));
        if (cart.remove(productId)) {
            catalog.findById(productId).ifPresent(p -> publish(EventTypes.CART_ITEM_REMOVED, userId, p, 0));
        }
        return toView(cart);
    }

    @Transactional
    public CartView clear(long userId) {
        carts.findByUserId(userId).ifPresent(cart -> {
            cart.clear();
            outbox.publish(Topics.CART_EVENTS, EventTypes.CART_CLEARED, String.valueOf(userId),
                    new CartCleared(userId, "USER_CLEARED"));
        });
        return view(userId);
    }

    // ------------------------------------------------------------------ Sự kiện

    @Transactional
    public void applyProduct(ProductChanged p, boolean discontinued) {
        CatalogItem item = catalog.findById(p.productId()).orElseGet(() -> new CatalogItem(p.productId()));
        item.update(p.name(), p.price(), p.imageUrl(), p.active() && !discontinued);
        catalog.save(item);
    }

    /** Khi đơn được tạo, xóa các sản phẩm đã đặt khỏi giỏ của khách. */
    @Transactional
    public void onOrderCreated(EventEnvelope env, OrderCreated order) {
        if (!guard.firstTime(env, CONSUMER)) {
            return;
        }
        carts.findByUserId(order.userId()).ifPresent(cart -> {
            order.items().forEach(line -> cart.remove(line.productId()));
            outbox.publish(Topics.CART_EVENTS, EventTypes.CART_CLEARED, String.valueOf(order.userId()),
                    new CartCleared(order.userId(), "ORDER_PLACED:" + order.orderId()));
        });
    }

    // ------------------------------------------------------------------ Nội bộ

    private void publish(String type, long userId, CatalogItem p, int quantity) {
        outbox.publish(Topics.CART_EVENTS, type, String.valueOf(userId),
                new CartItemChanged(userId, p.getProductId(), p.getName(), quantity, p.getPrice()));
    }

    private CartView toView(Cart cart) {
        Map<Long, CatalogItem> products = catalog.findAllById(
                        cart.getItems().stream().map(CartItem::getProductId).toList())
                .stream().collect(Collectors.toMap(CatalogItem::getProductId, Function.identity()));
        List<CartLine> lines = cart.getItems().stream().map(i -> {
            CatalogItem p = products.get(i.getProductId());
            BigDecimal price = p == null ? BigDecimal.ZERO : p.getPrice();
            return new CartLine(i.getProductId(), p == null ? "Sản phẩm #" + i.getProductId() : p.getName(),
                    p == null ? null : p.getImageUrl(), price, i.getQuantity(),
                    price.multiply(BigDecimal.valueOf(i.getQuantity())), p != null && p.isActive());
        }).toList();
        BigDecimal total = lines.stream().filter(CartLine::available).map(CartLine::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int qty = lines.stream().mapToInt(CartLine::quantity).sum();
        return new CartView(cart.getUserId(), lines, qty, total);
    }
}
