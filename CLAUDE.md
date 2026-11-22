# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Event-driven e-commerce platform: 11 Spring Boot 3.5 / Java 21 microservices talking over Apache Kafka (KRaft), Kafka Streams for real-time analytics, PostgreSQL (one database per service), Redis (product cache), and a React 18 + TypeScript + ECharts frontend. Maven multi-module reactor rooted at [pom.xml](pom.xml). Detailed docs (in Vietnamese): [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/API.md](docs/API.md), [docs/TESTING.md](docs/TESTING.md).

Code comments, log messages, and user-facing error strings are written in **Vietnamese** — keep new ones consistent.

## Commands

```bash
# Full stack (frontend :3000, gateway :8080, Kafka UI :8090, Postgres host port 5433)
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
- Topic names live in `libs/common/.../event/Topics.java`; event type names in `EventTypes.java`; payload records in `event/payload/*Events.java`. Every message is an `EventEnvelope` (eventId, eventType, version, occurredAt, source, aggregateId, payload). **Kafka key = aggregateId** so all events for one order/product stay ordered on one partition.
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
`POST /api/orders` saves a `PENDING` order + `OrderCreated` outbox row and returns 202. Inventory reserves atomically (`UPDATE ... WHERE available >= qty`, rows locked in productId order) → `InventoryReserved`/`InventoryRejected`. Order → `AWAITING_PAYMENT` and emits `PaymentRequested`; Payment replies `PaymentCompleted`/`PaymentFailed`; Order → `CONFIRMED` or `CANCELLED`. `OrderCancelled` triggers compensation (inventory release, payment refund). `OrderTimeoutJob` cancels orders stuck beyond `ORDER_TIMEOUT`. State machine: `order-service/.../domain/OrderStatus.java`. See `OrderSagaHandler`.

### Analytics
`analytics-service` runs a Kafka Streams topology (`streams/AnalyticsTopology.java`: windowed revenue 1m/5m/1h, order spike & payment-failure alerts, status counts, top products, funnel, active users) writing to `analytics-events`. `AnalyticsEventsConsumer` updates in-memory `DashboardState` and upserts aggregate tables (`schema.sql`); `DashboardBroadcaster` pushes SSE throttled to ≤1/s. The dashboard never queries business tables. `KafkaMonitor` reports consumer lag via AdminClient.

### simulator-service
Not a domain service — an HTTP client (`ShopClient`) that drives realistic traffic through the gateway (virtual customers, warehouse restocking, delivery staff). Controlled at `/admin/simulator` in the UI; disable autostart with `SIMULATOR_AUTOSTART=false`.

### Persistence & build details
- Schema is managed by Hibernate `ddl-auto: update` (no Flyway/Liquibase). Databases are created by [infra/postgres/init.sql](infra/postgres/init.sql) — a new service needs an entry there plus a `DB_URL` in docker-compose.
- Every service POM sets `<finalName>app</finalName>`; the shared [docker/service.Dockerfile](docker/service.Dockerfile) builds the whole reactor once and copies `services/${SERVICE}/target/app.jar`. A new module must be added to the root `pom.xml` `<modules>` and to `docker-compose.yml`.
- Service ports: gateway 8080, auth 8081, user 8082, product 8083, cart 8084, order 8085, inventory 8086, payment 8087, notification 8088, analytics 8089, simulator 8091.

### Tests
- Integration tests use `@EmbeddedKafka(kraft = true)` + H2 in `MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`, overriding properties in `@SpringBootTest(properties = ...)` (e.g. `shop.kafka.partitions=1`, `shop.outbox.poll-interval-ms=100`) and asserting async outcomes with Awaitility. See `OrderSagaIntegrationTest` as the template.
- Kafka Streams logic is tested with `TopologyTestDriver` (`AnalyticsTopologyTest`).
- `FlashSaleScenario` is shared between `FlashSaleH2Test` and `FlashSalePostgresTest` (Testcontainers).
- Test IDs TC01–TC10 referenced in test Javadoc map to [docs/TESTING.md](docs/TESTING.md).

### Frontend
`frontend/src/api/client.ts` wraps fetch with the bearer token from localStorage and exposes `streamUrl()` for SSE; `hooks/useEventSource.ts` consumes SSE streams. Pages split into `pages/shop` (customer) and `pages/admin` (staff, guarded by `components/Guards.tsx`). In Docker the app is served by Nginx ([frontend/nginx.conf](frontend/nginx.conf)) proxying `/api` to the gateway.
