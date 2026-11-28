package com.shop.recommendation.service;

import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.recommendation.RecommendationProperties;
import com.shop.recommendation.engine.Scoring;
import com.shop.recommendation.store.RecommendationStore;
import com.shop.recommendation.store.RecommendationStore.CatalogItem;
import com.shop.recommendation.store.RecommendationStore.DailyStat;
import com.shop.recommendation.store.RecommendationStore.Interaction;
import com.shop.recommendation.store.RecommendationStore.Pair;
import com.shop.recommendation.store.RecommendationStore.Stats;
import com.shop.recommendation.web.RecommendationDtos.AdminStats;
import com.shop.recommendation.web.RecommendationDtos.CategoryAffinity;
import com.shop.recommendation.web.RecommendationDtos.DayStats;
import com.shop.recommendation.web.RecommendationDtos.ModelStats;
import com.shop.recommendation.web.RecommendationDtos.PairDto;
import com.shop.recommendation.web.RecommendationDtos.PlacementStats;
import com.shop.recommendation.web.RecommendationDtos.RecommendationList;
import com.shop.recommendation.web.RecommendationDtos.RecommendedProduct;
import com.shop.recommendation.web.RecommendationDtos.Seed;
import com.shop.recommendation.web.RecommendationDtos.UserProfile;
import com.shop.support.web.Access;
import com.shop.support.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Bộ gợi ý lai (hybrid):
 * <ul>
 *   <li><b>similar</b> – "Sản phẩm tương tự": khách cũng xem (45%) + thường mua cùng (25%) + nội dung (30%).</li>
 *   <li><b>bought-together</b> – "Thường được mua cùng": lọc cộng tác trên đơn đã thanh toán.</li>
 *   <li><b>for-you</b> – "Dành cho bạn": hồ sơ sở thích (xem/giỏ/mua có suy giảm theo thời gian, danh mục quan tâm,
 *       từ khóa đã tìm) → láng giềng của các sản phẩm đã tương tác; đa dạng hóa theo danh mục; khách mới → thịnh hành.</li>
 *   <li><b>trending</b> – "Đang thịnh hành": xem + 3×giỏ + 5×mua theo ngày, chu kỳ bán rã vài ngày.</li>
 *   <li><b>cart</b> – "Có thể bạn cũng cần": mua cùng các món trong giỏ.</li>
 * </ul>
 * Mọi điểm được nhân hệ số chất lượng từ điểm đánh giá (Bayes) và mỗi gợi ý kèm lý do giải thích được.
 */
@Service
public class RecommendationService {

    public static final String SIMILAR = "similar";
    public static final String BOUGHT_TOGETHER = "bought-together";
    public static final String FOR_YOU = "for-you";
    public static final String TRENDING = "trending";
    public static final String CART = "cart";

    public static final Map<String, String> PLACEMENTS = new LinkedHashMap<>();

    static {
        PLACEMENTS.put(FOR_YOU, "Dành cho bạn");
        PLACEMENTS.put(SIMILAR, "Sản phẩm tương tự");
        PLACEMENTS.put(BOUGHT_TOGETHER, "Thường được mua cùng");
        PLACEMENTS.put(CART, "Gợi ý trong giỏ hàng");
        PLACEMENTS.put(TRENDING, "Đang thịnh hành");
    }

    static final int MAX_LIMIT = 24;
    private static final String[] STAFF = {Roles.ADMIN, Roles.ANALYST, Roles.PRODUCT_MANAGER};
    private static final Map<String, Double> KIND_WEIGHT = Map.of(
            LearningService.VIEW, 1.0, LearningService.CART, 3.0, LearningService.BUY, 5.0, LearningService.SEARCH, 0.5);

    private final RecommendationStore store;
    private final RecommendationProperties props;
    private final MeterRegistry meters;
    private final Clock clock;

    private record Model(Map<Long, CatalogItem> catalog, Map<Long, Stats> stats, Map<Long, Double> trend,
                         Instant loadedAt) {
        Stats stats(long id) {
            return stats.getOrDefault(id, Stats.EMPTY);
        }

        double quality(long id) {
            Stats s = stats(id);
            return Scoring.quality(s.ratingSum(), s.ratingCount());
        }

        boolean available(long id) {
            CatalogItem p = catalog.get(id);
            return p != null && p.active();
        }
    }

    private volatile Model model;

    public RecommendationService(RecommendationStore store, RecommendationProperties props, MeterRegistry meters,
                                 Optional<Clock> clock) {
        this.store = store;
        this.props = props;
        this.meters = meters;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    // ------------------------------------------------------------------ Gợi ý theo sản phẩm

    public RecommendationList similar(long productId, int limit) {
        Model m = model();
        CatalogItem base = m.catalog().get(productId);
        if (base == null) {
            return served(trendingList(m, limit(limit), null, Set.of(productId), SIMILAR));
        }
        Map<Long, Candidate> cand = new HashMap<>();
        Stats bs = m.stats(productId);
        for (Pair p : store.pairs(productId, LearningService.VIEWED, 50)) {
            double sim = Scoring.similarity(p.weight(), bs.viewers(), m.stats(p.relatedId()).viewers());
            candidate(cand, p.relatedId()).add(0.45 * sim, "Khách xem sản phẩm này cũng xem");
        }
        for (Pair p : store.pairs(productId, LearningService.BOUGHT, 50)) {
            double sim = Scoring.similarity(p.weight(), bs.buyers(), m.stats(p.relatedId()).buyers());
            candidate(cand, p.relatedId()).add(0.25 * sim, "Thường được mua cùng");
        }
        if (base.categoryId() != null) {
            m.catalog().values().stream()
                    .filter(c -> c.active() && base.categoryId().equals(c.categoryId()))
                    .forEach(c -> candidate(cand, c.productId()));
        }
        for (Candidate c : cand.values()) {
            CatalogItem item = m.catalog().get(c.id);
            if (item == null) {
                continue;
            }
            double content = Scoring.contentSimilarity(base.categoryId(), base.price(), base.name(),
                    item.categoryId(), item.price(), item.name());
            boolean samePrice = Scoring.priceSimilarity(base.price(), item.price()) >= 0.6;
            String why = base.categoryId() != null && base.categoryId().equals(item.categoryId())
                    ? "Cùng danh mục " + label(item.categoryName()) + (samePrice ? ", giá tương đương" : "")
                    : "Tương tự về giá và tên";
            c.add(0.30 * content, why);
        }
        List<Candidate> ranked = rank(m, cand.values(), id -> id != productId);
        return served(new RecommendationList(SIMILAR, "HYBRID", toItems(m, ranked, limit(limit))));
    }

    public RecommendationList boughtTogether(long productId, int limit) {
        Model m = model();
        Map<Long, Candidate> cand = new HashMap<>();
        Stats bs = m.stats(productId);
        for (Pair p : store.pairs(productId, LearningService.BOUGHT, 50)) {
            double sim = Scoring.similarity(p.weight(), bs.buyers(), m.stats(p.relatedId()).buyers());
            candidate(cand, p.relatedId()).add(sim, "Thường được mua cùng · " + (long) p.weight() + " đơn");
        }
        List<Candidate> ranked = rank(m, cand.values(), id -> id != productId);
        return served(new RecommendationList(BOUGHT_TOGETHER, "CO_PURCHASE", toItems(m, ranked, limit(limit))));
    }

    /** Gợi ý cho giỏ hàng: sản phẩm thường được mua cùng các món đang có trong giỏ. */
    public RecommendationList cart(Collection<Long> productIds, int limit) {
        Model m = model();
        int n = limit(limit);
        Set<Long> inCart = new HashSet<>(productIds);
        Map<Long, Candidate> cand = new HashMap<>();
        for (Long id : inCart.stream().limit(10).toList()) {
            Stats bs = m.stats(id);
            String name = m.catalog().containsKey(id) ? m.catalog().get(id).name() : "sản phẩm trong giỏ";
            for (Pair p : store.pairs(id, LearningService.BOUGHT, 20)) {
                double sim = Scoring.similarity(p.weight(), bs.buyers(), m.stats(p.relatedId()).buyers());
                candidate(cand, p.relatedId()).add(sim, "Thường được mua cùng " + name);
            }
        }
        List<RecommendedProduct> items = new ArrayList<>(
                toItems(m, rank(m, cand.values(), id -> !inCart.contains(id)), n));
        fillWithTrending(m, items, n, inCart);
        return served(new RecommendationList(CART, cand.isEmpty() ? "TRENDING" : "CO_PURCHASE", items));
    }

    // ------------------------------------------------------------------ Thịnh hành

    public RecommendationList trending(int limit, Long categoryId) {
        return served(trendingList(model(), limit(limit), categoryId, Set.of(), TRENDING));
    }

    private RecommendationList trendingList(Model m, int limit, Long categoryId, Set<Long> exclude, String placement) {
        List<RecommendedProduct> items = new ArrayList<>();
        Set<Long> skip = new HashSet<>(exclude);
        if (categoryId == null) {
            fillWithTrending(m, items, limit, skip);
        } else {
            List<Candidate> ranked = trendingCandidates(m, id -> categoryId.equals(m.catalog().get(id).categoryId())
                    && !skip.contains(id));
            items.addAll(toItems(m, ranked, limit));
        }
        return new RecommendationList(placement, "TRENDING", items);
    }

    private List<Candidate> trendingCandidates(Model m, Predicate<Long> filter) {
        List<Candidate> out = new ArrayList<>();
        m.trend().forEach((id, score) -> {
            if (m.available(id) && filter.test(id)) {
                out.add(new Candidate(id).add(score * m.quality(id), "Đang thịnh hành"));
            }
        });
        out.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed().thenComparingLong(c -> c.id));
        return out;
    }

    /** Bổ sung bằng sản phẩm thịnh hành (đa dạng danh mục), rồi đến sản phẩm mới nếu chưa có dữ liệu hành vi. */
    private void fillWithTrending(Model m, List<RecommendedProduct> items, int limit, Set<Long> exclude) {
        if (items.size() >= limit) {
            return;
        }
        Set<Long> used = new HashSet<>(exclude);
        items.forEach(i -> used.add(i.productId()));
        List<Candidate> trend = trendingCandidates(m, id -> !used.contains(id));
        List<Candidate> picked = Scoring.diversify(trend, c -> m.catalog().get(c.id).categoryId(),
                Math.max(2, limit / 3), limit - items.size());
        items.addAll(toItems(m, picked, limit - items.size()));
        if (items.size() < limit) {
            items.forEach(i -> used.add(i.productId()));
            m.catalog().values().stream()
                    .filter(p -> p.active() && !used.contains(p.productId()))
                    .sorted(Comparator.comparingLong(CatalogItem::productId).reversed())
                    .limit(limit - items.size())
                    .forEach(p -> items.add(item(p, 0, "Sản phẩm mới")));
        }
    }

    // ------------------------------------------------------------------ Cá nhân hóa

    private record Profile(Map<Long, Double> products, Map<Long, String> strongestKind,
                           Map<Long, Double> categories, List<String> searches, Set<Long> interacted,
                           Set<Long> bought) {
        boolean isEmpty() {
            return products.isEmpty() && categories.isEmpty() && searches.isEmpty();
        }
    }

    private Profile profile(Model m, long userId) {
        Instant now = clock.instant();
        double halfLife = props.profileHalfLife().toMillis();
        Map<Long, Double> products = new HashMap<>();
        Map<Long, String> strongest = new HashMap<>();
        Map<Long, Double> strongestWeight = new HashMap<>();
        Map<Long, Double> categories = new HashMap<>();
        Set<String> searches = new LinkedHashSet<>();
        Set<Long> interacted = new HashSet<>();
        Set<Long> bought = new HashSet<>();
        for (Interaction i : store.interactions(userId, now.minus(props.profileWindow()), 300)) {
            double w = KIND_WEIGHT.getOrDefault(i.kind(), 0.0)
                    * Scoring.decay(now.toEpochMilli() - i.occurredAt().toEpochMilli(), halfLife);
            Long category = i.categoryId();
            if (i.productId() != null) {
                interacted.add(i.productId());
                if (LearningService.BUY.equals(i.kind())) {
                    bought.add(i.productId());
                }
                products.merge(i.productId(), w, Double::sum);
                double base = KIND_WEIGHT.getOrDefault(i.kind(), 0.0);
                if (base > strongestWeight.getOrDefault(i.productId(), 0.0)) {
                    strongestWeight.put(i.productId(), base);
                    strongest.put(i.productId(), i.kind());
                }
                if (category == null && m.catalog().containsKey(i.productId())) {
                    category = m.catalog().get(i.productId()).categoryId();
                }
            }
            if (category != null) {
                categories.merge(category, w, Double::sum);
            }
            if (i.query() != null && searches.size() < 3) {
                searches.add(i.query());
            }
        }
        return new Profile(products, strongest, categories, new ArrayList<>(searches), interacted, bought);
    }

    public RecommendationList forYou(Optional<AuthenticatedUser> user, int limit) {
        int n = limit(limit);
        Model m = model();
        if (user.isEmpty()) {
            return served(trendingList(m, n, null, Set.of(), FOR_YOU));
        }
        return served(personalized(m, profile(m, user.get().userId()), n));
    }

    private RecommendationList personalized(Model m, Profile profile, int n) {
        if (profile.isEmpty()) {
            return trendingList(m, n, null, Set.of(), FOR_YOU);
        }
        Map<Long, Candidate> cand = new HashMap<>();

        // 1. Láng giềng (cùng xem / mua cùng) của các sản phẩm khách đã tương tác gần đây
        List<Map.Entry<Long, Double>> seeds = top(profile.products(), 8);
        double maxSeed = seeds.isEmpty() ? 1 : seeds.get(0).getValue();
        for (Map.Entry<Long, Double> seed : seeds) {
            long id = seed.getKey();
            double w = seed.getValue() / maxSeed;
            String why = seedReason(m, id, profile.strongestKind().get(id));
            Stats ss = m.stats(id);
            for (Pair p : store.pairs(id, LearningService.VIEWED, 20)) {
                double sim = Scoring.similarity(p.weight(), ss.viewers(), m.stats(p.relatedId()).viewers());
                candidate(cand, p.relatedId()).add(0.6 * w * sim, why);
            }
            for (Pair p : store.pairs(id, LearningService.BOUGHT, 20)) {
                double sim = Scoring.similarity(p.weight(), ss.buyers(), m.stats(p.relatedId()).buyers());
                candidate(cand, p.relatedId()).add(0.4 * w * sim, why);
            }
        }

        // 2. Sản phẩm thịnh hành trong các danh mục khách quan tâm nhất
        List<Map.Entry<Long, Double>> cats = top(profile.categories(), 2);
        double maxCat = cats.isEmpty() ? 1 : cats.get(0).getValue();
        for (Map.Entry<Long, Double> cat : cats) {
            List<Candidate> inCat = trendingCandidates(m, id -> cat.getKey().equals(m.catalog().get(id).categoryId()));
            double best = inCat.isEmpty() ? 1 : inCat.get(0).score;
            String name = inCat.isEmpty() ? null : m.catalog().get(inCat.get(0).id).categoryName();
            for (Candidate c : inCat.subList(0, Math.min(10, inCat.size()))) {
                candidate(cand, c.id).add(0.35 * (cat.getValue() / maxCat) * (0.3 + 0.7 * c.score / best),
                        "Vì bạn quan tâm " + label(name));
            }
        }

        // 3. Khớp từ khóa khách đã tìm kiếm
        double maxTrend = m.trend().values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        for (String q : profile.searches()) {
            m.catalog().values().stream()
                    .filter(p -> p.active() && Scoring.matchesQuery(p.name(), q))
                    .limit(10)
                    .forEach(p -> candidate(cand, p.productId()).add(
                            0.4 * (0.5 + 0.5 * m.trend().getOrDefault(p.productId(), 0.0) / maxTrend),
                            "Vì bạn đã tìm “" + q + "”"));
        }

        List<Candidate> ranked = rank(m, cand.values(), id -> !profile.interacted().contains(id));
        List<Candidate> diverse = Scoring.diversify(ranked, c -> m.catalog().get(c.id).categoryId(),
                Math.max(2, (n + 2) / 3), n);
        List<RecommendedProduct> items = new ArrayList<>(toItems(m, diverse, n));
        boolean personalized = !items.isEmpty();
        fillWithTrending(m, items, n, profile.interacted());
        // Khách đã tương tác với gần hết danh mục: vẫn trả đủ danh sách (thịnh hành / mua lại), không bao giờ rỗng
        if (items.size() < n) {
            Set<Long> used = new HashSet<>();
            items.forEach(i -> used.add(i.productId()));
            for (Candidate c : trendingCandidates(m, id -> !used.contains(id))) {
                if (items.size() >= n) {
                    break;
                }
                items.add(item(m.catalog().get(c.id), c.score,
                        profile.bought().contains(c.id) ? "Bạn từng mua – mua lại?" : "Đang thịnh hành"));
            }
        }
        return new RecommendationList(FOR_YOU, personalized ? "PERSONALIZED" : "TRENDING", items);
    }

    private static String seedReason(Model m, long id, String kind) {
        CatalogItem p = m.catalog().get(id);
        String name = p == null ? "sản phẩm #" + id : p.name();
        if (LearningService.BUY.equals(kind)) {
            return "Vì bạn đã mua " + name;
        }
        if (LearningService.CART.equals(kind)) {
            return "Vì bạn đã thêm " + name + " vào giỏ";
        }
        return "Vì bạn đã xem " + name;
    }

    // ------------------------------------------------------------------ Đo hiệu quả

    public void click(Optional<AuthenticatedUser> user, String placement, long productId) {
        if (!PLACEMENTS.containsKey(placement)) {
            throw ApiException.badRequest("Vị trí gợi ý không hợp lệ: " + placement);
        }
        Instant now = clock.instant();
        store.clicked(RecommendationStore.day(now), placement);
        user.ifPresent(u -> store.addClick(u.userId(), productId, placement, now));
        meters.counter("shop.recommendation.clicks", "placement", placement).increment();
    }

    private RecommendationList served(RecommendationList list) {
        try {
            store.served(RecommendationStore.day(clock.instant()), list.placement(), list.items().size());
        } catch (Exception e) {
            // Thống kê không được làm hỏng việc trả gợi ý
        }
        meters.counter("shop.recommendation.served", "placement", list.placement()).increment(list.items().size());
        return list;
    }

    // ------------------------------------------------------------------ Quản trị

    public AdminStats stats(AuthenticatedUser actor, int days) {
        Access.requireAnyRole(actor, STAFF);
        int d = Math.max(1, Math.min(days, 90));
        LocalDate from = RecommendationStore.day(clock.instant()).minusDays(d - 1L);
        Map<String, RecommendationStore.PlacementRow> rows = new HashMap<>();
        store.placementReport(from).forEach(r -> rows.put(r.placement(), r));
        List<PlacementStats> placements = new ArrayList<>();
        PLACEMENTS.forEach((key, label) -> {
            RecommendationStore.PlacementRow r = rows.getOrDefault(key,
                    new RecommendationStore.PlacementRow(key, 0, 0, 0, 0, 0));
            placements.add(new PlacementStats(key, label, r.requests(), r.impressions(), r.clicks(),
                    percent(r.clicks(), r.impressions()), r.conversions(), percent(r.conversions(), r.clicks()),
                    r.revenue()));
        });
        List<DayStats> daily = store.dailyReport(from).stream()
                .map(r -> new DayStats(r.day(), r.impressions(), r.clicks(), r.conversions())).toList();
        Model m = model();
        ModelStats model = new ModelStats(m.catalog().size(),
                m.catalog().values().stream().filter(CatalogItem::active).count(),
                store.count("SELECT COUNT(*) FROM rec_pairs WHERE kind = ? AND product_id < related_id", LearningService.BOUGHT),
                store.count("SELECT COUNT(*) FROM rec_pairs WHERE kind = ? AND product_id < related_id", LearningService.VIEWED),
                store.count("SELECT COUNT(*) FROM rec_interactions"),
                store.count("SELECT COUNT(DISTINCT user_id) FROM rec_interactions"),
                store.lastInteractionAt());
        List<PairDto> pairs = store.topPairs(LearningService.BOUGHT, 10).stream()
                .map(p -> new PairDto(p.productId(), name(m, p.productId()), p.relatedId(), name(m, p.relatedId()),
                        p.weight()))
                .toList();
        return new AdminStats(d, placements, daily, model, pairs);
    }

    public UserProfile explain(AuthenticatedUser actor, long userId, int limit) {
        Access.requireAnyRole(actor, STAFF);
        Model m = model();
        Profile p = profile(m, userId);
        List<Seed> seeds = top(p.products(), 8).stream()
                .map(e -> new Seed(e.getKey(), name(m, e.getKey()), p.strongestKind().get(e.getKey()), round(e.getValue())))
                .toList();
        List<CategoryAffinity> categories = top(p.categories(), 5).stream()
                .map(e -> new CategoryAffinity(e.getKey(), categoryName(m, e.getKey()), round(e.getValue())))
                .toList();
        // Không tính vào thống kê hiển thị (nhân viên xem thử)
        return new UserProfile(userId, seeds, categories, p.searches(), personalized(m, p, limit(limit)));
    }

    // ------------------------------------------------------------------ Tiện ích

    private static final class Candidate {
        final long id;
        double score;
        double best;
        String reason;

        Candidate(long id) {
            this.id = id;
        }

        Candidate add(double s, String why) {
            score += s;
            if (s > best || reason == null) {
                best = Math.max(best, s);
                reason = why;
            }
            return this;
        }
    }

    private static Candidate candidate(Map<Long, Candidate> cand, long id) {
        return cand.computeIfAbsent(id, Candidate::new);
    }

    /** Lọc sản phẩm còn bán, nhân hệ số chất lượng, sắp xếp giảm dần. */
    private static List<Candidate> rank(Model m, Collection<Candidate> cand, Predicate<Long> keep) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : cand) {
            if (c.score > 0 && m.available(c.id) && keep.test(c.id)) {
                c.score *= m.quality(c.id);
                out.add(c);
            }
        }
        out.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed().thenComparingLong(c -> c.id));
        return out;
    }

    private static List<RecommendedProduct> toItems(Model m, List<Candidate> ranked, int limit) {
        return ranked.stream().limit(Math.max(0, limit))
                .map(c -> item(m.catalog().get(c.id), c.score, c.reason)).toList();
    }

    private static RecommendedProduct item(CatalogItem p, double score, String reason) {
        return new RecommendedProduct(p.productId(), p.name(), p.price(), p.imageUrl(), p.categoryId(),
                p.categoryName(), round(score), reason);
    }

    private static List<Map.Entry<Long, Double>> top(Map<Long, Double> weights, int n) {
        return weights.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(n).toList();
    }

    private static String name(Model m, long id) {
        CatalogItem p = m.catalog().get(id);
        return p == null ? "Sản phẩm #" + id : p.name();
    }

    private static String categoryName(Model m, Long categoryId) {
        return m.catalog().values().stream().filter(p -> categoryId.equals(p.categoryId()))
                .map(CatalogItem::categoryName).filter(x -> x != null).findFirst().orElse("Danh mục #" + categoryId);
    }

    private static String label(String categoryName) {
        return categoryName == null ? "này" : categoryName;
    }

    private static int limit(int requested) {
        return Math.max(1, Math.min(requested, MAX_LIMIT));
    }

    private static double percent(long part, long whole) {
        return whole == 0 ? 0 : Math.round(10000.0 * part / whole) / 100.0;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** Danh mục + thống kê + điểm thịnh hành, lưu đệm {@code cacheTtl} (0 = luôn đọc mới). */
    private Model model() {
        Model current = model;
        Instant now = clock.instant();
        if (current != null && !props.cacheTtl().isZero()
                && current.loadedAt().plus(props.cacheTtl()).isAfter(now)) {
            return current;
        }
        LocalDate today = RecommendationStore.day(now);
        Map<Long, Double> trend = new HashMap<>();
        double halfLife = props.trendingHalfLifeDays();
        for (DailyStat d : store.daily(today.minusDays(props.trendingDays() - 1L))) {
            double age = Math.max(0, ChronoUnit.DAYS.between(d.day(), today));
            double score = Scoring.popularity(d.views(), d.carts(), d.purchases()) * Math.pow(0.5, age / halfLife);
            trend.merge(d.productId(), score, Double::sum);
        }
        Model next = new Model(store.catalog(), store.stats(), trend, now);
        model = next;
        return next;
    }

    /** Buộc đọc lại dữ liệu ở lần gọi tiếp theo (dùng trong test). */
    public void invalidate() {
        model = null;
    }
}
