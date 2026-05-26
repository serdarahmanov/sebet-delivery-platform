# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./mvnw clean package

# Run
./mvnw spring-boot:run

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=CartServicePromoFlowTest

# Run a single test method
./mvnw test -Dtest=CartServicePromoFlowTest#someMethod

# Compile without running tests
./mvnw compile
```

The integration test `CartServiceApplicationTests` is `@Disabled` — it requires full infrastructure (Redis, PostgreSQL, Kafka) and is not meant to run in isolation.

## Infrastructure Dependencies

| Dependency | Default | Purpose |
|---|---|---|
| Redis | `localhost:6379` | Cart state + GET response cache |
| PostgreSQL | via JPA/Flyway | Local projection tables |
| Kafka | `localhost:9092` | Inbound domain events from other services |
| promotion-service | `http://promotion-service:8080` | HTTP evaluation of promo codes |

## Architecture

### High-Level Request Flow

Every cart-mutating request follows this pipeline:

```
Controller (X-User-Id header)
  → CartService (optimistic-lock read → mutate → CAS save)
  → CartResponseBuilder.build()
      → CartValidationService.validate()
          → ProductCartValidator      (uses cart_product_projections table)
          → InventoryCartValidator    (uses cart_inventory_projections table)
          → StoreCartValidator        (uses cart_store_projections table)
          → PromotionClient.evaluatePromotions()  (HTTP → promotion-service)
          → PromotionValidationResultApplier
      → CartCalculationService.calculate()
      → RedisCartMapper.toCartResponse()
```

The GET `/api/cart` endpoint is served by `CartResponseCacheService`, which keeps a secondary Redis cache of the fully-built `CartResponse` and rebuilds it on any cart mutation.

### Cart State (Redis)

`RedisCart` is stored at key `cart:{userId}` with a 7-day TTL. It contains:
- `List<RedisCartItem>` — items keyed by generated `cartItemId` (UUID)
- `List<RedisCartPromoCode>` — promo codes per store with state (`SAVED` | `SELECTED`)
- `long version` — optimistic-lock token; starts at `0`, incremented on every successful CAS save

A companion key `cart:{userId}:v` stores only the version integer as a plain string. The Lua CAS script reads and writes this key directly without JSON parsing.

Cart items are scoped to a `(productId, storeId)` pair. The `basketId` in responses is `{cartId}:{storeId}`.

### Optimistic Locking (CAS)

All cart mutations use compare-and-swap instead of pessimistic Redis locks:

1. **Read** — `CartRedisRepository.findByUserId()` returns the `RedisCart` (including its current `version`).
2. **Mutate** — `CartService` modifies the in-memory `RedisCart` object.
3. **CAS save** — `CartRedisRepository.saveIfVersionMatches(userId, cart, expectedVersion)` executes a Lua script that:
   - Reads `cart:{userId}:v`.
   - If it matches `expectedVersion`, atomically writes both `cart:{userId}` (full JSON) and `cart:{userId}:v` (incremented version) via `SETEX`.
   - Returns `1` on success, `0` on conflict.
4. **Conflict** — If `saveIfVersionMatches` returns `false`, `CartVersionConflictException` is thrown → HTTP 409.

`CartLockService` (Redis SETNX pessimistic lock) has been **deleted**. There is no lock TTL to worry about.

### Local Projections (PostgreSQL)

Three Flyway-managed tables keep a local read-only copy of domain data from other services. Validators query these instead of calling remote services synchronously:

| Table | Entity | Updated by Kafka topic |
|---|---|---|
| `cart_product_projections` | `ProductProjection` | `product-events` |
| `cart_inventory_projections` | `InventoryProjection` | `inventory-events` |
| `cart_store_projections` | `StoreProjection` | `store-events` |

Projection event handlers (`ProductEventHandler`, `InventoryEventHandler`, `StoreEventHandler`) apply version checks to discard stale out-of-order events.

#### DB Query Timeouts

Three layers of protection guard against slow or hanging projection queries:

| Layer | Setting | Value |
|---|---|---|
| Global JPA query timeout | `jakarta.persistence.query.timeout` in `application.yaml` | 3 000 ms |
| Per-query `@QueryHints` | `ProductProjectionRepository.findByProductIdInAndStoreIdIn` | 2 000 ms |
| Per-query `@QueryHints` | `InventoryProjectionRepository.findByProductIdInAndStoreIdIn` | 1 500 ms |
| Per-query `@QueryHints` | `StoreProjectionRepository.findByStoreIdIn` | 1 000 ms |
| HikariCP connection acquire | `spring.datasource.hikari.connection-timeout` | 3 500 ms |

Per-query `@QueryHints` take precedence over the global property for methods that declare them. The global value acts as a safety net for any repository method without an explicit hint.

### Validation & Issues

Each validator adds issues to a `CartValidationAccumulator`. Issues have a `scope` (`CART` | `ITEM` | `STORE_BASKET`), a `severity` (`BLOCKING` | `WARNING`), and a typed code enum. A store basket's `canCheckout` field is false if any blocking issue exists at the store or item level.

Only items with no blocking issues are forwarded to the promotion evaluation request.

### Promo Code Lifecycle

1. `claimPromoCode` — validates the code exists and is claimable (calls `buildStoreBasketResponse` which triggers a full validation+promotion eval), then persists it as `SAVED`.
2. `applyPromoCodes` — transitions claimed codes to `SELECTED`/`SAVED` based on the client's selection.
3. `SELECTED` codes are sent to the promotion-service on every `validate` call.
4. `EXCLUSIVE` vs `STACKABLE` selection-type logic is enforced in `CartService.normalizeFromBasketPromoCards`.

### Two `CartResponse` Types

There are currently two `CartResponse` classes in different packages:
- `com.sebet.cartservice.cart.dto.CartResponse` — returned by mutating endpoints (`CartService`)
- `com.sebet.cartservice.cart.dto.getcart.CartResponse` — returned by the GET endpoint (`CartResponseCacheService`)

`RedisCartMapper` builds the `dto.CartResponse`. `GetCartResponseBuilder` (a separate class) builds the `dto.getcart.CartResponse`.

### Cart Schema Migration

`RedisCart` carries a `schemaVersion` integer (currently `CURRENT_SCHEMA_VERSION = 4`). On read, `CartRedisRepository.findByUserId()` enforces three rules:

| Stored version | Action |
|---|---|
| `< CURRENT` | Migrate via `CartSchemaMigrationService` chain → CAS save (using the pre-migration version as expected) → return migrated cart. On failure: log, return empty (original untouched in Redis) |
| `== CURRENT` | Return as-is |
| `> CURRENT` | Return empty without deleting (rolling-deployment guard — new pod wrote this) |

Migration steps live in `cart/migration/steps/`. Each step implements `CartMigrationStep` and is auto-discovered by Spring. To bump the schema: increment `CURRENT_SCHEMA_VERSION` and add a `CartMigrationStepVNToVN1` class annotated `@Component`.

### Checkout Executor

`CheckoutExecutorConfig` defines a `ThreadPoolTaskExecutor` (`core=4`, `max=16`, `queue=100`, prefix=`checkout-`) used by `CheckoutService` to offload checkout validation off the HTTP request thread.

- Rejection policy: explicit `AbortPolicy`. `CheckoutService` catches `RejectedExecutionException` from `CompletableFuture.supplyAsync()` before `.get()`, records a metric, and returns 503.
- MDC context is propagated to executor threads via `MdcTaskDecorator`.

**`.get()` gate timeout** (applied in both `initiateCheckout` and `confirmCheckout`):

Both flows submit a **single future** — `cartValidationService.validateForCheckout()` — which handles projection validation, unified delivery checkout-quote HTTP call, and promotion evaluation in one pass.

| Future | Timeout | Rationale |
|---|---|---|
| `cartValidationService.validateForCheckout()` | **8s** | Covers sequential DB queries (up to ~5.5s under pool pressure) + unified delivery checkout-quote HTTP + promotion HTTP (500ms) |

`TimeoutException` → future cancelled → HTTP 503.

## Observability

### Distributed Tracing (OpenTelemetry Java Agent)

The OTel Java Agent v2.9.0 is **bundled in the Docker image** at `/opt/otel/opentelemetry-javaagent.jar` and activated unconditionally via `ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/opentelemetry-javaagent.jar"` in `Dockerfile`. No `micrometer-tracing` dependency is needed — the agent provides all instrumentation at the JVM level.

**What the agent auto-instruments:**
- Spring MVC HTTP requests → spans with HTTP metadata
- WebClient calls to promotion-service and delivery-service → child spans + outbound W3C `traceparent` header injection
- Kafka consumer/producer → spans linked to producer context
- JDBC (PostgreSQL projection queries) → spans per query
- Redis operations → spans per command

**Trace pipeline:**
```
OTel Agent (in-process)
  → OTLP/gRPC → OTel Collector (:4317)
      → Tempo (:3200)
          → Grafana (:3000)
```

**OTel env vars** (set in both `docker-compose.yaml` and `k8s/deployment.yaml`):

| Variable | Value | Note |
|---|---|---|
| `OTEL_SERVICE_NAME` | `cart-service` | |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://otel-collector:4317` | |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` | |
| `OTEL_TRACES_EXPORTER` | `otlp` | |
| `OTEL_METRICS_EXPORTER` | `none` | Metrics go via Prometheus scrape, not OTel |
| `OTEL_LOGS_EXPORTER` | `none` | Logs go via logback to stdout, not OTel |
| `OTEL_PROPAGATORS` | `tracecontext,baggage` | W3C format |

The agent writes `trace_id`, `span_id`, `trace_flags` into MDC automatically. The prod `LogstashEncoder` in `logback-spring.xml` picks these up as top-level JSON fields, so every log line in prod carries trace coordinates.

### MDC / Structured Logging

Three components cover all thread contexts:

| Thread type | Component | MDC keys set |
|---|---|---|
| HTTP request threads | `MdcRequestFilter` | `requestId` (from `X-Request-Id` header or generated UUID), `userId`, `method`, `path` |
| Kafka consumer threads | `MdcKafkaRecordInterceptor` | `requestId` (new UUID per record), `kafkaTopic`, `kafkaPartition`, `kafkaOffset`, `kafkaKey` |
| Checkout executor threads | `MdcTaskDecorator` | Full copy of calling thread's MDC at submit time |

Both `HttpPromotionClient` and `HttpDeliveryAvailabilityClient` forward `requestId` as `X-Request-Id` on outbound calls for cross-service log correlation.

`logback-spring.xml`: coloured text format for non-prod; `LogstashEncoder` JSON for the `prod` profile.

### Metrics (Micrometer + Prometheus)

Custom counters and timers are in `CartMetrics`. Prometheus scrapes `/actuator/prometheus` every 15 seconds. Grafana dashboard: `docker/grafana/dashboards/cart-service.json`.

**Key metrics:**

| Metric | Type | Tags |
|---|---|---|
| `cart.items.added/removed/batch_upserted/basket.cleared` | Counter | `store_id` |
| `cart.promo.claimed` | Counter | `store_id`, `result` |
| `cart.checkout.initiate.*` | Counter | `store_id`, `scope` |
| `cart.checkout.confirm.*` | Counter | `store_id` |
| `cart.checkout.initiate.executor_rejected` | Counter | `store_id` |
| `cart.checkout.confirm.executor_rejected` | Counter | `store_id` |
| `cart.validation.duration` | Timer/Histogram | `blocking_issues` |
| `cart.checkout.initiate.duration` | Timer/Histogram | — |
| `cart.checkout.confirm.duration` | Timer/Histogram | — |
| `cart.promotion.call.duration` | Timer/Histogram | — |
| `cart.delivery.call.duration` | Timer/Histogram | `store_id`, `type` |
| `cart.delivery.quote.cache_hit/fetched/fetch_failed` | Counter | `store_id`, `type` |
| `cart.response.cache.hit/miss` | Counter | — |
| `cart.promotion.degraded` | Counter | `reason` |

**Known metric gaps** (not yet implemented):
- No projection staleness health indicator (all three projection tables have `projectionUpdatedAt` / `updatedAt` but no `HealthIndicator` queries them)

### Local Observability Stack (docker-compose)

| Service | Port | Role |
|---|---|---|
| `otel-collector` | 4317 (gRPC), 4318 (HTTP) | Receives OTLP spans, batches, forwards to Tempo |
| `tempo` | 3200 (HTTP API) | Trace storage, 48h retention |
| `prometheus` | 9090 | Metrics scraper |
| `grafana` | 3000 | Dashboards (anonymous admin, no login required) |

## Key Design Patterns

- **Validators are stateless Spring `@Component`s** — they receive `(RedisCart, CartValidationAccumulator, CartValidationLookupContext)` and mutate the accumulator. The lookup context is pre-fetched in bulk before validators run.
- **Projection event handlers use optimistic versioning** — if `data.version <= projection.version`, the event is dropped as stale.
- **`CartService` always calls `cartResponseCacheService.refreshCachedCartResponseIfPresent(userId)`** after persisting a cart change.
- **`PromotionClient` is a synchronous blocking WebFlux call** (`WebClient.block()`). If it returns null or fails, an empty promotion response is used (no promo discounts applied).
- **`DeliveryFeeResolver` is always in-memory** — it mutates the `RedisCart` object passed to it but never saves to Redis. All delivery quote changes flow into the cart object and are persisted by the outer CAS save in `CartService`.
- **Single CAS save per mutation** — `CartService` calls `cartRedisRepository.saveIfVersionMatches()` exactly once per mutating operation. There is no secondary save path anywhere else in the write path.
- **Optimistic locking with Lua CAS** — the Lua script on `CartRedisRepository` reads the `cart:{userId}:v` integer key, compares it to `expectedVersion`, and only writes if they match. The cart JSON and version key are both updated in the same atomic `SETEX` pair. Concurrent writes cause a 409 on the slower request.
- **`CartValidationService.resolveDeliveryFeeOnly()`** — targeted method that refreshes only the delivery fee in an existing `CartValidationResult` without re-running projection validators or promotion evaluation. Used by `setScheduledDelivery()` rejection path.
- **`confirmCheckout`: CAS save before Kafka publish** — the basket is removed from the cart and persisted via CAS before `CheckoutConfirmedEvent` is published to Kafka. This prevents a phantom order if a concurrent write causes the CAS to fail after the event is already on the bus. Trade-off: if the CAS succeeds but the Kafka publish fails, the basket is gone and the client receives 503 — an acknowledged gap until an outbox pattern is introduced.
- **`initiateCheckout`: dirty-flag conditional save** — the cart is only saved to Redis when the in-memory state actually changed (new address, delivery quote updated or cleared, schedule type reset). `cart.touch()` and the CAS save are skipped when nothing changed (e.g. cached quote reused, same address). The save is placed before the blocking-issue check so real state corrections (address change, slot reset) are persisted even on a blocked checkout.
- **Two delivery quote validity thresholds** — `isQuoteValid()` (plain `> now`) is used by the regular `resolve()` path for cart browsing and mutations. `isQuoteValidForCheckout()` (`> now + 30s`) is used exclusively by `resolveForCheckout()` so a nearly-expired quote is re-fetched before the Kafka → order-service → delivery-service fee-lock chain begins. There is no separate expiry guard in `confirmCheckout` — the 30-second buffer in `resolveForCheckout` is the single enforcement point.
