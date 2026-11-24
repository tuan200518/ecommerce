# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Event-driven e-commerce platform: 17 Spring Boot 3.5 / Java 21 microservices talking over Apache Kafka (KRaft), Kafka Streams for real-time analytics, PostgreSQL (one database per service), Redis (product cache), and a React 18 + TypeScript + ECharts frontend. Maven multi-module reactor rooted at [pom.xml](pom.xml). Detailed docs (in Vietnamese): [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/API.md](docs/API.md), [docs/TESTING.md](docs/TESTING.md).

Code comments, log messages, and user-facing error strings are written in **Vietnamese** — keep new ones consistent.

## Commands

```bash
# Full stack (frontend :3000, gateway :8080, Kafka UI :8090, Postgres host port 5433,
#             Grafana :3001 admin/admin, Prometheus 127.0.0.1:9090)
docker compose up -d --build
docker compose down            # add -v to wipe data

# Backend build + all tests (no Docker needed; FlashSalePostgresTest auto-skips without Docker)
mvn clean install
mvn test

# Single module / single test
mvn -pl services/order-service test
mvn -pl services/order-service test -Dtest=OrderSagaIntegrationTest
mvn -pl services/order-service test -Dtest=OrderSagaIntegrationTest#happyPath*
# If libs changed, add -am so libs/common and libs/service-support are rebuilt:
mvn -pl services/order-service -am test -Dtest=OrderSagaIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false

# Run one service locally against Docker infra
docker compose up -d postgres redis kafka kafka-ui
mvn -pl services/order-service spring-boot:run
# Defaults expect Postgres on 5432; Docker publishes 5433, so override DB_URL, e.g. (PowerShell):
#   $env:DB_URL="jdbc:postgresql://localhost:5433/order_db"
# Kafka from host is localhost:9094 (the default in application.yml)

# Frontend (Node 20+); Vite dev server on :5173 proxies /api → VITE_GATEWAY_URL (default localhost:8080)
cd frontend && npm install && npm run dev
npm run typecheck              # tsc --noEmit; there is no lint or test script
npm run build

# Load tests (k6 via compose profile)
docker compose --profile loadtest run --rm k6 run /scripts/simulate-traffic.js
docker compose --profile loadtest run --rm k6 run -e BUYERS=200 /scripts/flash-sale.js
```

Seeded logins: `admin@shop.local / Admin@123`, `customer@shop.local / Customer@123` (others in README).

## Architecture

### Communication rules
- **No synchronous calls between services.** REST is only client → API Gateway → service. All service-to-service interaction goes through Kafka.
- Services needing another domain's data keep a **local read-model fed by `product-events`** (Order: `ProductSnapshot`, Cart: `CatalogItem`, Inventory: `Inventory`). Never read another service's database.
- Topic names live in `libs/common/.../event/Topics.java`; event type names in `EventTypes.java`; payload records in `event/payload/*Events.java`. Every message is an `EventEnvelope` (eventId, eventType, version, occurredAt, source, aggregateId, payload, correlationId). **Kafka key = aggregateId** so all events for one order/product stay ordered on one partition.
- Evolve payload records **backward-compatibly**: append new components at the end, keep a secondary constructor with the old arity (see `OrderEvents.OrderCreated`, `UserEvents.UserRegistered`), and tolerate the field being null in old messages.
- correlationId flows automatically: gateway `CorrelationIdFilter` → `X-Correlation-Id` → service `CorrelationFilter` (thread + MDC) → `OutboxService` stores it → `OutboxRelay` sends Kafka header `correlationId` → `CorrelationRecordInterceptor` restores it in consumers, so follow-up events inherit it. Never set it by hand; `EventEnvelope.create` reads `Correlation.current()`. Logs print it via `logging.pattern.correlation` (default set by `CorrelationLoggingEnvironmentPostProcessor`).
- Topics (and their `-dlt` twins) are created by `KafkaSupportAutoConfiguration` from `Topics.ALL`; broker auto-create is disabled. A new topic must be added to `Topics.ALL`.

### Shared libraries
- `libs/common` — event envelope/payloads/topics, `JwtService`, `Roles`, `AuthHeaders`, `AuthenticatedUser`.
- `libs/service-support` — Spring Boot **auto-configuration** (registered in `META-INF/spring/...AutoConfiguration.imports`) that every service gets just by depending on it:
  - `OutboxService.publish(topic, eventType, aggregateId, payload)` — `Propagation.MANDATORY`; must be called inside the business transaction. `OutboxRelay` polls `outbox_events` and publishes in order. **Services never send to `KafkaTemplate` directly.**
  - `IdempotencyGuard.firstTime(env, consumerName)` — `MANDATORY` transaction; inserts into `processed_events` with `ON CONFLICT DO NOTHING`.
  - `EventReader.read(json)` / `.payload(env, Type.class)` — throws `MalformedEventException`, which the `DefaultErrorHandler` sends straight to DLT (no retry). Other exceptions retry with exponential backoff (500ms ×2, 3 attempts) then go to `<topic>-dlt`.
  - `SupportSchemaInitializer` creates the `outbox_events` / `processed_events` tables (SQL must work on both PostgreSQL and H2).
  - Web: `CurrentUserArgumentResolver` lets controllers take `AuthenticatedUser` (required → 401) or `Optional<AuthenticatedUser>`; `Access.requireAnyRole(...)`; `ApiException` + `GlobalExceptionHandler` produce RFC 7807 problem+json; `PageResponse` for paged lists.
  - Tunables under `shop.kafka.*` / `shop.outbox.*` (`ShopProperties`).

### Consumer pattern (follow this when adding listeners)
`messaging/*Listeners.java` holds thin `@KafkaListener(id = "<service>.<topic>", topics = Topics.X)` methods — the `id` is the consumer group id. They delegate to a `@Transactional` handler that switches on `env.eventType()`, wraps side-effecting branches in `guard.firstTime(env, CONSUMER)`, and emits follow-up events via `OutboxService` in the same transaction. Listener concurrency is 3 (= partition count).

### Security
API Gateway (`services/api-gateway`) validates the JWT (header or `?access_token=` for SSE/EventSource), **strips any client-supplied `X-User-*` headers**, then sets `X-User-Id / X-User-Email / X-User-Roles` for downstream services. Public routes and role-gated prefixes are defined in `AccessPolicy.java`; route → service mapping is in `ApiGatewayApplication.routes()`. Services re-check fine-grained permissions themselves (defense in depth). `JWT_SECRET` is shared by auth-service and the gateway.

### Order saga (orchestrated by order-service)
`POST /api/orders` saves a `PENDING` order (total includes a shipping fee: free from 500k, else 30k) + `OrderCreated` outbox row and returns 202. Two things then happen in parallel and may arrive in any order:
- Inventory reserves atomically (`UPDATE ... WHERE available >= qty`, rows locked in productId order) → `InventoryReserved`/`InventoryRejected`.
- fraud-service scores the order → `RiskAssessed` on `fraud-alert-events`. HIGH risk (`requiresReview`) → order `UNDER_REVIEW` (stock stays reserved) until staff decide → `RiskReviewed` (approve continues, reject cancels).

- promotion-service prices **every** order (flash-sale prices + optional `voucherCode`, reserving quota/usage with conditional UPDATEs in `PromotionLedger`) → `PromotionApplied` (Order stores discounts and lowers `totalAmount`) or `PromotionRejected` (order cancelled with the reason — never charged a different price than quoted).

The order moves to `AWAITING_PAYMENT` (emitting `PaymentRequested` with the discounted amount) only when `inventoryReserved`, `riskCleared` **and** `promotionCleared` are all set on the `Order` (`promotionCleared` null = pre-2.1 order, treated as cleared). Payment replies `PaymentCompleted`/`PaymentFailed` → `CONFIRMED` or `CANCELLED`. After `OrderConfirmed`, shipping-service creates a shipment; its `ShipmentStatusChanged(HANDED_OVER/IN_TRANSIT)` / `ShipmentDelivered` drive the order to `SHIPPING` / `DELIVERED` — staff can no longer set those via `PUT /api/orders/{id}/status` (cancel only). `OrderCancelled` triggers compensation in inventory, payment, fraud, shipping, loyalty and review. `OrderTimeoutJob` cancels orders stuck beyond `ORDER_TIMEOUT` (PENDING/AWAITING_PAYMENT) or `ORDER_REVIEW_TIMEOUT` (UNDER_REVIEW). `FRAUD_CHECK_ENABLED=false` / `PROMOTION_CHECK_ENABLED=false` skip the risk / promotion gate. State machine: `order-service/.../domain/OrderStatus.java`. See `OrderSagaHandler`.

### Ecommerce 2.0 services
- **fraud-service** (8092): `RiskRules` is a pure, explainable rule set (thresholds in `shop.fraud.*`). A review requires score ≥ `review-score` **and** ≥ `min-signals-for-review` signals, so no single rule can block an order. Review API `/api/fraud/**` (ADMIN/ANALYST).
- **shipping-service** (8094): `Shipment` state machine `CREATED → PACKED → HANDED_OVER → IN_TRANSIT → DELIVERED` (+`CANCELLED`). Legacy `OrderStatusChanged(SHIPPING/DELIVERED)` is synced into shipments **without** publishing events (prevents loops on replay).
- **loyalty-service** (8093): ledger `PointTransaction` with a unique `(userId, type, referenceId)`; balance/tier derive from it. Earn on `OrderConfirmed` (×tier multiplier), first-order/referral/review bonuses, `REVERSAL` on cancel or hidden review. Referral codes arrive via `UserRegistered.referralCode`.
- **review-service** (8095): `Purchase` read-model from order-events gates reviews (delivered buyers only, one per product); Q&A and moderation under `/api/reviews/admin/**`.
- product-service publishes `ProductViewed` / `SearchPerformed` to `user-activity-events` (best-effort: `ProductController.track` swallows failures).
- New services consume topics from the beginning, so anything with side effects on old events is guarded by event age (`shop.fraud.max-event-age`, `shop.loyalty.notify-max-age`, `shop.promotion.max-event-age`).

### Ecommerce 2.1 (promotion, security, ops)
- **promotion-service** (8096): `PricingEngine` is pure pricing logic shared by checkout `POST /api/promotions/quote` and saga pricing; `redemptions` records what each order holds so `OrderCancelled` returns flash-sale quota and voucher uses. Loyalty redemption: loyalty-service deducts points (`REDEMPTION`, row locked) and emits `PointsRedeemed` with voucher specs → promotion issues a personal voucher (idempotent by `redemptionId`). Seeds `WELCOME10`/`FREESHIP`/`GIAM50K` + a flash sale when empty (`PROMOTION_SEED`).
- **Audit**: staff/security actions call `AuditTrail.record(actor, action, resourceType, id, detail)` (service-support; `MANDATORY` tx, same transaction as the action) or `recordFailure(...)` (`REQUIRES_NEW`, for failures like bad logins) → `audit-events` → **audit-service** (8097, ADMIN-only `/api/audit`, alerts admins on account lock / one IP failing on many accounts). Add an audit call when adding a staff mutation.
- **Gateway**: `RateLimitFilter` (order -90, after JWT -100; fixed 1-min windows in Redis, fail-open; buckets in `RateLimitPolicy`, limits `RATE_LIMIT_*`). `ClientIp` trusts `X-Real-IP` only from internal addresses and the gateway overwrites `X-Client-Ip` for downstream.
- **auth-service**: N consecutive bad passwords → temporary lock (423), `shop.auth.max-failed-logins` / `lock-duration`.
- **Metrics**: `MetricsEnvironmentPostProcessor` tags every metric with `application` and enables latency histograms; custom meters `shop.outbox.pending`, `shop.kafka.dlt`, `shop.gateway.rate.limited`, `shop.auth.logins`, `shop.promotion.*`. Alert rules in `infra/prometheus/alerts.yml`, Grafana dashboard provisioned from `infra/grafana/`.
- Perl/sed one-liners with `|` or `#` delimiters have repeatedly corrupted files here (those characters appear in Java/TS/YAML) — prefer the Edit tool for multi-line source edits.

### Analytics
`analytics-service` runs a Kafka Streams topology (`streams/AnalyticsTopology.java`: windowed revenue 1m/5m/1h, order spike & payment-failure alerts, status counts, top products, funnel, active users) writing to `analytics-events`. `AnalyticsEventsConsumer` updates in-memory `DashboardState` and upserts aggregate tables (`schema.sql`); `DashboardBroadcaster` pushes SSE throttled to ≤1/s. The dashboard never queries business tables. `KafkaMonitor` reports consumer lag via AdminClient.

### simulator-service
Not a domain service — an HTTP client (`ShopClient`) that drives realistic traffic through the gateway (virtual customers incl. reviews/Q&A and occasional "suspicious" big orders; staff bots for restocking, shipment processing via `/api/shipments`, and fraud review via `analyst@shop.local`). Controlled at `/admin/simulator` in the UI; disable autostart with `SIMULATOR_AUTOSTART=false`.

### Persistence & build details
- Schema is managed by Hibernate `ddl-auto: update` (no Flyway/Liquibase). Consequences: new columns on existing tables must be **nullable** (NOT NULL fails on tables with rows), and Hibernate 6's CHECK constraints on `@Enumerated(STRING)` columns are not updated when enum values are added — see `OrderSchemaPatches` (drops them on startup).
- Databases are created by the idempotent [infra/postgres/init.sql](infra/postgres/init.sql) (`\gexec`), run both by initdb and by the compose `db-init` job on every `up` (so existing volumes get new DBs). A new service needs an entry there plus a `DB_URL` in docker-compose.
- All 15 DB services share one PostgreSQL: connection budget is `max_connections=200` (postgres `command` in compose) vs `DB_POOL_SIZE` (default 5, set via `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE` in `x-service-env`) × number of services. Exceeding it shows up as `too many clients already` / "Unable to determine Dialect" at startup and 500s from the gateway.
- Every service POM sets `<finalName>app</finalName>`; the shared [docker/service.Dockerfile](docker/service.Dockerfile) builds the whole reactor once and copies `services/${SERVICE}/target/app.jar`. A new module must be added to the root `pom.xml` `<modules>` and to `docker-compose.yml`.
- Service ports: gateway 8080 (actuator on internal management port 9080), auth 8081, user 8082, product 8083, cart 8084, order 8085, inventory 8086, payment 8087, notification 8088, analytics 8089, simulator 8091, fraud 8092, loyalty 8093, shipping 8094, review 8095, promotion 8096, audit 8097. A new service also needs a scrape target in [infra/prometheus/prometheus.yml](infra/prometheus/prometheus.yml). A new service also needs a `ServiceUrls` field, a route in `ApiGatewayApplication`, `AccessPolicy` rules (first matching `RoleRule` wins) and the URL env var on the gateway in docker-compose.

### Tests
- Integration tests use `@EmbeddedKafka(kraft = true)` + H2 in `MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`, overriding properties in `@SpringBootTest(properties = ...)` (e.g. `shop.kafka.partitions=1`, `shop.outbox.poll-interval-ms=100`) and asserting async outcomes with Awaitility. See `OrderSagaIntegrationTest` as the template.
- Kafka Streams logic is tested with `TopologyTestDriver` (`AnalyticsTopologyTest`).
- Service-level tests without a broker (e.g. `FraudServiceTest`, `LoyaltyServiceTest`) call the `handle*Event` methods directly with `EventEnvelope.create(...)` and assert on `outbox_events` rows, using `spring.kafka.listener.auto-startup=false`, `shop.outbox.relay-enabled=false`, `shop.kafka.create-topics=false`.
- In `OrderSagaIntegrationTest`, orders need a `RiskAssessed` and a `PromotionApplied` event (`clearChecks(id)` = `lowRisk(id)` + `noPromotion(id)`) before they can reach `AWAITING_PAYMENT`.
- H2 rejects `VALUE` as a column name (hence `vouchers.discount_value`); SQL in `PromotionLedger`/`SupportSchemaInitializer` must run on both H2 and PostgreSQL.
- `FlashSaleScenario` is shared between `FlashSaleH2Test` and `FlashSalePostgresTest` (Testcontainers).
- Test IDs TC01–TC10 referenced in test Javadoc map to [docs/TESTING.md](docs/TESTING.md).

### Frontend
`frontend/src/api/client.ts` wraps fetch with the bearer token from localStorage and exposes `streamUrl()` for SSE; `hooks/useEventSource.ts` consumes SSE streams. Pages split into `pages/shop` (customer) and `pages/admin` (staff, guarded by `components/Guards.tsx`). In Docker the app is served by Nginx ([frontend/nginx.conf](frontend/nginx.conf)) proxying `/api` to the gateway.
