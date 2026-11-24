package com.shop.simulator.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.shop.simulator.SimulatorProperties;
import com.shop.simulator.client.ShopApiException;
import com.shop.simulator.client.ShopClient;
import com.shop.simulator.engine.ActivityLog.Kind;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.shop.simulator.engine.RandomData.chance;
import static com.shop.simulator.engine.RandomData.rnd;

/**
 * Bộ máy mô phỏng khách hàng ảo.
 * <p>
 * Khách "đến cửa hàng" theo quá trình Poisson (khoảng cách ngẫu nhiên giữa các lượt), tốc độ có thể dao động
 * theo hình sin để mô phỏng giờ cao điểm. Mỗi phiên là một hành trình ngẫu nhiên:
 * <pre>
 * xem danh sách/tìm kiếm → xem chi tiết 1-3 sản phẩm ─┬─▶ rời đi (browse-only)
 *                                                     └─▶ thêm 1-4 món vào giỏ ─┬─▶ bỏ giỏ
 *                                                                               └─▶ đặt hàng (thẻ / ví / thẻ bị từ chối)
 *                                                                                    └─▶ đôi khi hủy đơn sau vài giây
 * </pre>
 * Ngoài ra: khách quay lại đánh giá / hỏi đáp sản phẩm, và thỉnh thoảng một "khách đáng ngờ" (tài khoản mới,
 * đơn rất lớn) để Fraud Service gắn cờ.
 * <p>
 * Song song có 3 "nhân viên ảo": nhập kho khi sắp hết hàng, xử lý vận đơn (đóng gói → bàn giao → đang giao →
 * đã giao) qua Shipping Service, và xác minh đơn bị gắn cờ rủi ro – để hệ thống chạy liên tục nhiều giờ.
 */
@Component
public class CustomerSimulator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CustomerSimulator.class);
    private static final Duration CATALOG_TTL = Duration.ofMinutes(1);

    public record Status(boolean running, Instant startedAt, SimulatorSettings settings, double currentRate,
                         int activeSessions, int customers, int catalogSize, Map<String, Long> stats,
                         long orderValue, List<ActivityLog.Entry> recent) {
    }

    private final ShopClient client;
    private final SimulatorProperties props;
    private final ActivityLog activity = new ActivityLog();
    private final List<VirtualCustomer> customers = new CopyOnWriteArrayList<>();
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final ExecutorService sessions = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2,
            Thread.ofPlatform().name("sim-scheduler-", 0).daemon(true).factory());

    private volatile SimulatorSettings settings;
    private volatile Catalog catalog = Catalog.empty();
    /** Sản phẩm đang flash sale còn suất + mã giảm giá công khai (làm mới cùng danh mục). */
    private volatile List<Long> flashProducts = List.of();
    private volatile List<String> voucherCodes = List.of();
    private volatile Instant catalogLoadedAt = Instant.EPOCH;
    private volatile boolean running;
    private volatile Instant startedAt;
    private Thread arrivals;
    private final List<ScheduledFuture<?>> bots = new ArrayList<>();

    public CustomerSimulator(ShopClient client, SimulatorProperties props) {
        this.client = client;
        this.props = props;
        this.settings = SimulatorSettings.from(props);
        customers.add(new VirtualCustomer("customer@shop.local", props.customerPassword(), "Nguyễn Văn Khách",
                VirtualCustomer.Persona.REGULAR));
        for (int i = 1; i <= props.customerCount(); i++) {
            customers.add(new VirtualCustomer("customer" + i + "@shop.local", props.customerPassword(),
                    RandomData.fullName(), VirtualCustomer.Persona.random()));
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.autostart()) {
            start();
        }
    }

    // ------------------------------------------------------------------ Điều khiển

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        startedAt = Instant.now();
        arrivals = Thread.ofPlatform().name("sim-arrivals").daemon(true).start(this::arrivalLoop);
        bots.add(scheduler.scheduleWithFixedDelay(this::safeRestock, 10, 20, TimeUnit.SECONDS));
        bots.add(scheduler.scheduleWithFixedDelay(this::safeFulfill, 10, 10, TimeUnit.SECONDS));
        bots.add(scheduler.scheduleWithFixedDelay(this::safeRiskReview, 15, 15, TimeUnit.SECONDS));
        log.info("Bắt đầu mô phỏng khách hàng ảo: {}", settings);
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (arrivals != null) {
            arrivals.interrupt();
        }
        bots.forEach(f -> f.cancel(false));
        bots.clear();
        log.info("Dừng mô phỏng khách hàng ảo");
    }

    public void update(SimulatorSettings next) {
        this.settings = next.validated();
        log.info("Cập nhật cấu hình mô phỏng: {}", this.settings);
    }

    /** Dồn {@code orders} lượt mua (chắc chắn đặt hàng) trong {@code seconds} giây → kích hoạt cảnh báo đột biến. */
    public void burst(int orders, int seconds) {
        int n = Math.max(1, Math.min(orders, 500));
        long window = Math.max(1, Math.min(seconds, 300)) * 1000L;
        for (int i = 0; i < n; i++) {
            scheduler.schedule(() -> launch(true), rnd().nextLong(window), TimeUnit.MILLISECONDS);
        }
        activity.record(Kind.BURST, "admin", "đợt mua dồn dập: " + n + " đơn trong " + seconds + " giây");
        log.info("Burst {} đơn trong {}s", n, seconds);
    }

    public void resetStats() {
        activity.reset();
    }

    public Status status() {
        Map<String, Long> stats = new java.util.LinkedHashMap<>();
        for (Kind k : Kind.values()) {
            stats.put(k.name(), activity.count(k));
        }
        return new Status(running, startedAt, settings, Math.round(currentRate() * 10) / 10.0, active.get(),
                customers.size(), catalog.size(), stats, activity.orderValue(), activity.recent(40));
    }

    @PreDestroy
    public void shutdown() {
        stop();
        scheduler.shutdownNow();
        sessions.shutdownNow();
    }

    // ------------------------------------------------------------------ Luồng khách đến

    private void arrivalLoop() {
        while (running) {
            try {
                if (catalog.isEmpty() || Instant.now().isAfter(catalogLoadedAt.plus(CATALOG_TTL))) {
                    refreshCatalog();
                }
                if (catalog.isEmpty()) {
                    Thread.sleep(5000); // hệ thống chưa sẵn sàng (đang khởi động) → chờ
                    continue;
                }
                // Khoảng cách giữa 2 khách theo phân phối mũ (quá trình Poisson)
                double perMs = currentRate() / 60_000.0;
                long delay = (long) (-Math.log(1 - rnd().nextDouble()) / perMs);
                Thread.sleep(Math.min(delay, 15_000));
                if (running) {
                    launch(false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                activity.record(Kind.ERROR, "simulator", e.getMessage());
                sleepQuietly(2000);
            }
        }
    }

    double currentRate() {
        SimulatorSettings s = settings;
        if (!s.wave() || startedAt == null) {
            return s.sessionsPerMinute();
        }
        double seconds = Duration.between(startedAt, Instant.now()).toSeconds();
        // Chu kỳ 10 phút: dao động ±60% quanh tốc độ cấu hình
        return Math.max(1, s.sessionsPerMinute() * (1 + 0.6 * Math.sin(2 * Math.PI * seconds / 600)));
    }

    private void launch(boolean forceOrder) {
        if (catalog.isEmpty()) {
            return;
        }
        if (active.get() >= settings.maxConcurrentSessions()) {
            return; // quá tải phía mô phỏng → bỏ lượt này
        }
        active.incrementAndGet();
        sessions.submit(() -> {
            try {
                runSession(forceOrder);
            } catch (ShopApiException e) {
                activity.record(Kind.ERROR, "khách ảo", "HTTP " + e.status() + ": " + e.getMessage());
            } catch (Exception e) {
                activity.record(Kind.ERROR, "khách ảo", e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                active.decrementAndGet();
            }
        });
    }

    // ------------------------------------------------------------------ Một phiên mua sắm

    private void runSession(boolean forceOrder) throws InterruptedException {
        SimulatorSettings s = settings;
        activity.record(Kind.SESSION, null, null);
        if (!forceOrder && chance(s.suspiciousRate())) {
            suspiciousSession();
            return;
        }
        VirtualCustomer c = chooseCustomer(s);
        if (!forceOrder && chance(s.reviewRate())) {
            afterSalesSession(c);
            return;
        }

        // 1. Xem danh sách / tìm kiếm
        if (chance(0.3)) {
            client.products("q=" + URLEncoder.encode(RandomData.searchTerm(), StandardCharsets.UTF_8) + "&size=12");
        } else {
            client.products("page=" + rnd().nextInt(2) + "&size=12" + (chance(0.4) ? "&sort=price_asc" : ""));
        }
        think(300, 1200);

        // 2. Xem chi tiết vài sản phẩm
        int views = 1 + rnd().nextInt(3);
        for (int i = 0; i < views; i++) {
            Catalog.Item item = chance(0.7) ? catalog.pickPopular() : catalog.pickAny();
            client.product(item.id());
            client.availability(item.id());
            think(400, 1500);
        }
        if (!forceOrder && chance(s.browseOnlyRate())) {
            activity.record(Kind.BROWSE_ONLY, c.name(), "xem " + views + " sản phẩm rồi rời đi");
            return;
        }

        // 3. Thêm vào giỏ
        int lines = c.persona().lines();
        int added = 0;
        for (int i = 0; i < lines; i++) {
            Catalog.Item item = catalog.pickPopular();
            int qty = c.persona().quantity();
            try {
                withAuth(c, t -> client.addToCart(t, item.id(), qty));
                added++;
            } catch (ShopApiException e) {
                if (e.status() != 400) {
                    throw e; // 400 = sản phẩm ngừng bán / vượt giới hạn giỏ → bỏ qua món này
                }
            }
            think(300, 900);
        }
        // 3b. "Săn" flash sale: thêm 1 sản phẩm đang giảm giá (giới hạn mỗi khách do Promotion Service kiểm soát)
        List<Long> flash = flashProducts;
        if (!flash.isEmpty() && chance(s.flashSaleRate())) {
            long productId = flash.get(rnd().nextInt(flash.size()));
            try {
                withAuth(c, t -> client.addToCart(t, productId, 1));
                added++;
                activity.record(Kind.FLASH_SALE, c.name(), "săn flash sale sản phẩm #" + productId);
            } catch (ShopApiException e) {
                if (e.status() != 400) {
                    throw e;
                }
            }
        }
        if (added == 0) {
            return;
        }
        if (!forceOrder && chance(s.cartAbandonRate())) {
            activity.record(Kind.ABANDON_CART, c.name(), "bỏ giỏ hàng có " + added + " món");
            return;
        }

        // 4. Đặt hàng từ giỏ hiện tại (có thể còn món từ lần bỏ giỏ trước)
        JsonNode cart = withAuth(c, client::cart);
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode line : cart.path("items")) {
            if (line.path("available").asBoolean()) {
                items.add(Map.of("productId", line.path("productId").asLong(),
                        "quantity", Math.min(line.path("quantity").asInt(), 20)));
            }
        }
        if (items.isEmpty()) {
            return;
        }
        String method = chance(s.paymentFailureRate()) ? "MOCK_FAIL" : chance(0.6) ? "MOCK_CARD" : "MOCK_WALLET";
        String voucher = pickVoucher(c, s, items);
        think(500, 1500);
        JsonNode order = withAuth(c, t -> client.placeOrder(t, items, RandomData.address(c.name()), method, voucher));
        long orderId = order.path("id").asLong();
        double total = order.path("totalAmount").asDouble();
        activity.addOrderValue(total);
        activity.record(Kind.ORDER, c.name(), "đặt đơn #" + orderId + " – " + items.size() + " món – "
                + money(total) + " – " + method + (voucher == null ? "" : " – mã " + voucher));

        // 5. Đôi khi đổi ý và hủy đơn
        if (chance(s.cancelRate())) {
            scheduler.schedule(() -> {
                try {
                    withAuth(c, t -> client.cancelOrder(t, orderId));
                    activity.record(Kind.CANCEL, c.name(), "hủy đơn #" + orderId);
                } catch (ShopApiException e) {
                    // đơn đã bị hủy/giao trước đó → không sao
                }
            }, RandomData.thinkMillis(3000, 10000), TimeUnit.MILLISECONDS);
        }
    }

    /** Khách thử một mã công khai; chỉ dùng khi báo giá xác nhận mã hợp lệ cho giỏ hiện tại (như người thật). */
    private String pickVoucher(VirtualCustomer c, SimulatorSettings s, List<Map<String, Object>> items) {
        List<String> codes = voucherCodes;
        if (codes.isEmpty() || !chance(s.voucherRate())) {
            return null;
        }
        String code = codes.get(rnd().nextInt(codes.size()));
        try {
            JsonNode quote = withAuth(c, t -> client.quote(t, items, code));
            if (quote.path("voucherValid").asBoolean() && quote.path("warnings").isEmpty()
                    && quote.path("voucherDiscount").asDouble() > 0) {
                activity.record(Kind.VOUCHER, c.name(), "áp mã " + code + " giảm "
                        + money(quote.path("voucherDiscount").asDouble()));
                return code;
            }
        } catch (ShopApiException e) {
            log.debug("Báo giá thất bại: {}", e.getMessage());
        }
        return null;
    }

    private VirtualCustomer chooseCustomer(SimulatorSettings s) {
        if (chance(s.newCustomerRate())) {
            String name = RandomData.fullName();
            String email = "sim." + System.currentTimeMillis() + "." + rnd().nextInt(1000) + "@shop.local";
            try {
                String token = client.register(email, props.customerPassword(), name);
                VirtualCustomer c = new VirtualCustomer(email, props.customerPassword(), name,
                        VirtualCustomer.Persona.random());
                tokens.put(email, token);
                customers.add(c);
                activity.record(Kind.REGISTER, name, "đăng ký tài khoản mới " + email);
                return c;
            } catch (ShopApiException e) {
                activity.record(Kind.ERROR, name, "đăng ký thất bại: " + e.getMessage());
            }
        }
        return customers.get(rnd().nextInt(customers.size()));
    }

    // ------------------------------------------------------------------ Nhân viên ảo

    private void safeRestock() {
        try {
            if (running && settings.autoRestock()) {
                restockBot();
            }
        } catch (Exception e) {
            activity.record(Kind.ERROR, "NV kho ảo", e.getMessage());
        }
    }

    private void safeFulfill() {
        try {
            if (running && settings.autoFulfill()) {
                fulfillBot();
            }
        } catch (Exception e) {
            activity.record(Kind.ERROR, "NV giao hàng ảo", e.getMessage());
        }
    }

    /** Nhập hàng cho sản phẩm dưới ngưỡng (bỏ qua sản phẩm Flash Sale để giữ kịch bản Flash Sale). */
    private void restockBot() {
        VirtualCustomer staff = staff();
        JsonNode low = withAuth(staff, client::lowStock);
        for (JsonNode inv : low.path("content")) {
            String name = inv.path("productName").asText();
            if (name.startsWith("[FLASH")) {
                continue;
            }
            int qty = 80 + rnd().nextInt(120);
            withAuth(staff, t -> client.restock(t, inv.path("productId").asLong(), qty));
            activity.record(Kind.RESTOCK, "NV kho ảo", "nhập " + qty + " × " + name
                    + " (còn " + inv.path("available").asInt() + ")");
        }
    }

    /**
     * Nhân viên kho/giao hàng ảo xử lý vận đơn của Shipping Service theo hàng đợi:
     * chờ đóng gói ≥ 5s → đóng gói; đóng gói ≥ 8s → bàn giao; bàn giao ≥ 8s → đang giao; đang giao ≥ 15s → đã giao.
     * Order Service tự chuyển đơn sang SHIPPING / DELIVERED khi nhận shipping-events.
     */
    private void fulfillBot() {
        VirtualCustomer staff = staff();
        Instant now = Instant.now();
        moveShipments(staff, "IN_TRANSIT", "DELIVERED", now.minusSeconds(15), Kind.DELIVER, "đã giao vận đơn ");
        moveShipments(staff, "HANDED_OVER", "IN_TRANSIT", now.minusSeconds(8), Kind.SHIP, "đang giao vận đơn ");
        moveShipments(staff, "PACKED", "HANDED_OVER", now.minusSeconds(8), Kind.SHIP, "bàn giao vận đơn ");
        moveShipments(staff, "CREATED", "PACKED", now.minusSeconds(5), Kind.PACK, "đóng gói vận đơn ");
    }

    private void moveShipments(VirtualCustomer staff, String from, String to, Instant olderThan, Kind kind, String text) {
        JsonNode page = withAuth(staff, t -> client.shipments(t, from));
        int moved = 0;
        for (JsonNode s : page.path("content")) {
            Instant updated = Instant.parse(s.path("updatedAt").asText());
            if (updated.isBefore(olderThan) && moved < 25) {
                long id = s.path("id").asLong();
                try {
                    withAuth(staff, t -> client.updateShipment(t, id, to));
                    activity.record(kind, "NV vận chuyển ảo", text + s.path("trackingNumber").asText()
                            + " (đơn #" + s.path("orderId").asLong() + ")");
                    moved++;
                } catch (ShopApiException e) {
                    // vận đơn vừa bị hủy / trạng thái đã đổi → bỏ qua
                }
            }
        }
    }

    private void safeRiskReview() {
        try {
            if (running && settings.autoRiskReview()) {
                riskReviewBot();
            }
        } catch (Exception e) {
            activity.record(Kind.ERROR, "NV xác minh ảo", e.getMessage());
        }
    }

    /**
     * Nhân viên xác minh rủi ro ảo: hồ sơ chờ ≥ 45 giây → "gọi điện xác nhận". Điểm rất cao (≥ 90) bị từ chối,
     * còn lại được chấp nhận – mô phỏng bước review thủ công (hệ thống không tự kết luận gian lận).
     */
    private void riskReviewBot() {
        VirtualCustomer analyst = new VirtualCustomer(props.analystEmail(), props.analystPassword(), "analyst",
                VirtualCustomer.Persona.REGULAR);
        JsonNode page = withAuth(analyst, t -> client.riskCases(t, "PENDING_REVIEW"));
        Instant cutoff = Instant.now().minusSeconds(45);
        for (JsonNode c : page.path("content")) {
            if (!Instant.parse(c.path("createdAt").asText()).isBefore(cutoff)) {
                continue;
            }
            long orderId = c.path("orderId").asLong();
            int score = c.path("score").asInt();
            boolean approve = score < 90;
            String note = approve ? "Đã gọi điện xác nhận với khách" : "Không xác minh được thông tin người mua";
            try {
                withAuth(analyst, t -> client.reviewRisk(t, orderId, approve, note));
                activity.record(Kind.RISK_REVIEW, "NV xác minh ảo",
                        (approve ? "chấp nhận" : "từ chối") + " đơn #" + orderId + " (" + score + " điểm)");
            } catch (ShopApiException e) {
                // đơn vừa bị hủy / đã được người thật xử lý → bỏ qua
            }
        }
    }

    /** Khách "đáng ngờ": tài khoản vừa tạo đặt số lượng lớn sản phẩm đắt tiền → Fraud Service gắn cờ. */
    private void suspiciousSession() throws InterruptedException {
        String name = RandomData.fullName();
        String email = "sus." + System.currentTimeMillis() + "." + rnd().nextInt(1000) + "@shop.local";
        String token = client.register(email, props.customerPassword(), name);
        VirtualCustomer c = new VirtualCustomer(email, props.customerPassword(), name, VirtualCustomer.Persona.BIG_SPENDER);
        tokens.put(email, token);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Catalog.Item item = catalog.pickExpensive();
            if (items.stream().noneMatch(m -> m.get("productId").equals(item.id()))) {
                items.add(Map.of("productId", item.id(), "quantity", 8 + rnd().nextInt(5)));
            }
        }
        think(300, 800);
        JsonNode order = withAuth(c, t -> client.placeOrder(t, items, RandomData.address(name), "MOCK_CARD"));
        activity.addOrderValue(order.path("totalAmount").asDouble());
        activity.record(Kind.SUSPICIOUS, name, "tài khoản mới đặt đơn #" + order.path("id").asLong() + " – "
                + money(order.path("totalAmount").asDouble()));
    }

    /** Khách quay lại: đánh giá một sản phẩm đã nhận, hoặc đặt câu hỏi về sản phẩm. */
    private void afterSalesSession(VirtualCustomer c) {
        // Đôi khi đổi điểm thưởng lấy voucher (Loyalty → Promotion qua Kafka)
        if (chance(0.25)) {
            long balance = withAuth(c, client::loyalty).path("balance").asLong();
            String reward = balance >= 300 ? "FIXED_20K" : balance >= 200 ? "FREESHIP" : null;
            if (reward != null) {
                try {
                    JsonNode result = withAuth(c, t -> client.redeem(t, reward));
                    activity.record(Kind.REDEEM, c.name(), "đổi " + result.path("pointsSpent").asLong()
                            + " điểm lấy \"" + result.path("rewardName").asText() + "\"");
                    return;
                } catch (ShopApiException e) {
                    log.debug("Đổi điểm thất bại: {}", e.getMessage());
                }
            }
        }
        JsonNode orders = withAuth(c, client::myOrders);
        for (JsonNode o : orders.path("content")) {
            if (!"DELIVERED".equals(o.path("status").asText())) {
                continue;
            }
            for (JsonNode item : o.path("items")) {
                long productId = item.path("productId").asLong();
                JsonNode eligibility = withAuth(c, t -> client.reviewEligibility(t, productId));
                if (eligibility.path("canReview").asBoolean()) {
                    int rating = RandomData.rating();
                    withAuth(c, t -> client.submitReview(t, productId, rating, RandomData.reviewComment(rating)));
                    activity.record(Kind.REVIEW, c.name(), rating + "★ cho " + item.path("productName").asText());
                    return;
                }
            }
        }
        if (chance(0.3) && !catalog.isEmpty()) {
            Catalog.Item item = catalog.pickPopular();
            withAuth(c, t -> client.askQuestion(t, item.id(), RandomData.question()));
            activity.record(Kind.QUESTION, c.name(), "hỏi về " + item.name());
        }
    }

    private VirtualCustomer staff() {
        return new VirtualCustomer(props.staffEmail(), props.staffPassword(), "staff", VirtualCustomer.Persona.REGULAR);
    }

    // ------------------------------------------------------------------ Hỗ trợ

    private void refreshCatalog() {
        try {
            List<Catalog.Item> items = new ArrayList<>();
            for (JsonNode p : client.products("size=100").path("content")) {
                if (p.path("active").asBoolean() && !p.path("sku").asText().startsWith("FLS-")) {
                    items.add(new Catalog.Item(p.path("id").asLong(), p.path("sku").asText(), p.path("name").asText(),
                            p.path("price").asDouble(), p.path("categoryId").isNull() ? null : p.path("categoryId").asLong()));
                }
            }
            if (!items.isEmpty()) {
                // Giữ nguyên thứ tự "độ hot" nếu danh mục không đổi
                if (catalog.size() != items.size()) {
                    catalog = Catalog.of(items);
                }
                catalogLoadedAt = Instant.now();
            }
        } catch (ShopApiException e) {
            log.debug("Chưa tải được danh mục sản phẩm: {}", e.getMessage());
        }
        refreshPromotions();
    }

    private void refreshPromotions() {
        try {
            List<Long> flash = new ArrayList<>();
            for (JsonNode sale : client.flashSales()) {
                if ("ACTIVE".equals(sale.path("status").asText())) {
                    for (JsonNode item : sale.path("items")) {
                        if (item.path("remaining").asInt() > 0) {
                            flash.add(item.path("productId").asLong());
                        }
                    }
                }
            }
            List<String> codes = new ArrayList<>();
            for (JsonNode v : client.publicVouchers()) {
                codes.add(v.path("code").asText());
            }
            flashProducts = List.copyOf(flash);
            voucherCodes = List.copyOf(codes);
        } catch (ShopApiException e) {
            log.debug("Chưa tải được khuyến mãi: {}", e.getMessage());
        }
    }

    /** Gọi API với token của khách; token hết hạn / chưa có → đăng nhập lại một lần. */
    private JsonNode withAuth(VirtualCustomer c, Function<String, JsonNode> call) {
        String token = tokens.get(c.email());
        if (token == null) {
            token = client.login(c.email(), c.password());
            tokens.put(c.email(), token);
        }
        try {
            return call.apply(token);
        } catch (ShopApiException e) {
            if (e.status() != 401) {
                throw e;
            }
            String fresh = client.login(c.email(), c.password());
            tokens.put(c.email(), fresh);
            return call.apply(fresh);
        }
    }

    private static void think(int minMs, int maxMs) throws InterruptedException {
        Thread.sleep(RandomData.thinkMillis(minMs, maxMs));
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String money(double v) {
        return NumberFormat.getNumberInstance(Locale.of("vi", "VN")).format(Math.round(v)) + " ₫";
    }
}
