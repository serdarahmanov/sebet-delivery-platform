# Cart Service

`cart-service` is a Spring Boot microservice that manages user carts in the Sebet delivery platform.

It combines:
- Redis cart state (`cart:{userId}`)
- projection read models in Postgres (product, inventory, store)
- Kafka-driven projection updates
- promotion validation integration
- a lightweight cached `GET /api/cart` response for frontend rendering

## Tech Stack
- Java 17
- Spring Boot 4.0.x
- Spring Web MVC + Validation
- Spring Data Redis
- Spring Data JPA
- Spring Kafka
- PostgreSQL driver
- Maven Wrapper

## Identity Contract
The service does not parse JWTs.

User identity must be passed by upstream infrastructure via:
- `X-User-Id` request header

## API Surface
Base path: `/api/cart`

### Lightweight cart read
- `GET /api/cart`

This endpoint returns a frontend-oriented lightweight response from `com.sebet.cartservice.cart.dto.getcart.*`.

### Other cart operations
- `POST /api/cart/items`
- `POST /api/cart/items/batch`
- `PATCH /api/cart/items/{cartItemId}`
- `DELETE /api/cart/items/{cartItemId}`
- `POST /api/cart/store-baskets/{storeId}/promo-codes/claim`
- `POST /api/cart/store-baskets/{storeId}/promo-codes/apply`
- `DELETE /api/cart/store-baskets/{storeId}/promo-codes/{code}`
- `POST /api/cart/validate`
- `POST /api/cart/store-baskets/{storeId}/validate`
- `DELETE /api/cart/store-baskets/{basketId}`
- `DELETE /api/cart`

Note: non-GET endpoints still use internal/verbose response DTOs where applicable.

## GET /api/cart Caching Model
Redis cache key:
- `cart-response:{userId}`

TTL:
- 2 minutes

Rules:
1. `GET /api/cart` returns cached response immediately if present.
2. If cache is missing, it builds response from current Redis cart + validation + calculation, caches it, and returns it.
3. RedisCart mutation endpoints do not create this cache when missing.
4. After RedisCart mutation, cache is refreshed only if cache already exists.

Implementation entrypoint:
- `CartResponseCacheService#getOrBuildCartResponse`

## GET /api/cart Response Behavior
The lightweight GET response is built by `GetCartResponseBuilder` and follows these rules:

- hides item-level issue details from payload
- hides promo issue details from payload
- does not include store-basket issue model
- includes only store-level issues in basket `issues`
- filters out hard-invalid items (for response only, Redis cart is untouched)
- computes basket summary from included valid items
- applies both item-level and promo-code discounts in `itemsSubtotalAfterDiscount`
- sets `basketTotal` equal to `itemsSubtotalAfterDiscount`
- includes `estimatedDeliveryTime` only for available/non-blocked stores
- `appliedPromoCodes` includes only applied promo codes without promo issues

Current delivery estimation implementation:
- `MockDeliveryServiceClient` returns static range data (placeholder until real HTTP integration)

## Redis Storage
- Cart key prefix: `cart:`
- Cart version key: `cart:{userId}:v` — plain integer string; read/written atomically by the Lua CAS script without JSON parsing
- Cart TTL: 7 days (applied to both `cart:{userId}` and `cart:{userId}:v`)
- GET cart response cache key prefix: `cart-response:`
- GET cart response cache TTL: 2 minutes

## Optimistic Locking

Cart mutations use compare-and-swap (CAS) instead of pessimistic Redis locks. There is no `CartLockService`.

**Flow for every mutating operation:**

1. `CartRedisRepository.findByUserId()` — reads `RedisCart` (includes current `version` field).
2. `CartService` records `expectedVersion = cart.getVersion()` and applies in-memory mutations.
3. `CartRedisRepository.saveIfVersionMatches(userId, cart, expectedVersion)` — runs a Lua script:
   - Reads `cart:{userId}:v`.
   - If `cart:{userId}:v == expectedVersion`: atomically writes `cart:{userId}` (full JSON, `SETEX`) and `cart:{userId}:v` (incremented version, `SETEX`), returns `1`.
   - Otherwise: returns `0` (no writes).
4. If the result is `0`, `CartVersionConflictException` is thrown → **HTTP 409**.

**New cart creation race:** Two concurrent requests both see no cart and attempt to create one. The first CAS (with `expectedVersion = 0`) succeeds. The second gets a conflict and re-reads the just-created cart from Redis.

**`cart:{userId}:v` nil handling:** The Lua script treats a missing version key as `"0"`, so the first save of a brand-new cart (expectedVersion = 0) always succeeds.

## Validation and Calculation Pipeline
Validation (`CartValidationService`):
1. Bulk-fetch product/inventory/store lookup context from projections (DB queries with per-query timeouts)
2. Validators for product, inventory, and store consistency
3. Delivery fee resolution (in-memory only — no Redis save)
4. Promotion evaluation HTTP call
5. Promotion result merge into validation result

Calculation (`CartCalculationService`):
- item subtotals and totals
- basket-level discount math
- promo discount handling

**Single CAS save:** `CartService` calls `saveIfVersionMatches()` exactly once per mutation, after all in-memory changes (including delivery quote updates from `DeliveryFeeResolver`) are complete. `DeliveryFeeResolver` never writes to Redis itself.

### DB Query Timeouts

Three layers of protection guard against slow or hanging projection queries:

| Layer | Value |
|---|---|
| Global JPA query timeout (`jakarta.persistence.query.timeout` in `application.yaml`) | 3 000 ms |
| `ProductProjectionRepository.findByProductIdInAndStoreIdIn` (`@QueryHints`) | 2 000 ms |
| `InventoryProjectionRepository.findByProductIdInAndStoreIdIn` (`@QueryHints`) | 1 500 ms |
| `StoreProjectionRepository.findByStoreIdIn` (`@QueryHints`) | 1 000 ms |
| HikariCP `connection-timeout` (max wait to acquire a pool connection) | 3 500 ms |

Per-query `@QueryHints` override the global value. The global value is a safety net for repository methods without an explicit hint.

## Kafka Event Processing
Listeners:
- product events
- inventory events
- store events

Projection handlers update read models in Postgres.

### Product event safety behavior
`ProductEventHandler` ignores invalid/unsupported product event types safely:
- null event
- missing event id
- missing/blank event type
- unknown event type

These cases log warnings and return normally (no hard fail for unsupported product event types).

Real processing failures (for example DB errors) still propagate and are handled by Kafka error handling.

### Kafka error handling / DLT
`KafkaConsumerConfig` defines:
- `DefaultErrorHandler` with fixed backoff retries (3 retries, 1s)
- `DeadLetterPublishingRecoverer` that publishes to `{originalTopic}.DLT`
- recovered records are committed (`setCommitRecovered(true)`)

## Configuration
Main runtime config file:
- `src/main/resources/application.yaml`

Includes:
- Redis host/port
- Kafka bootstrap + consumer/producer serializers
- topic names under `app.kafka.topics.*`
- downstream service base URLs (`promotion`, `product`, `store`, `delivery`)
- Global JPA query timeout and HikariCP `connection-timeout`

## Run Locally
```powershell
.\mvnw.cmd spring-boot:run
```

## Tests
```powershell
.\mvnw.cmd test
```

Recent coverage includes:
- lightweight GET cart builder behavior
- cart-response cache behavior
- product event handler safety behavior for unknown/invalid events

## Cart Schema Migration

`RedisCart` carries an integer `schemaVersion` field. The current version is `RedisCart.CURRENT_SCHEMA_VERSION`.

**Version decision table (applied in `CartRedisRepository.findByUserId`):**

| Condition | Action |
|---|---|
| `version == CURRENT` | Use as-is |
| `version < CURRENT` | Attempt migration chain → CAS save (using the pre-migration version as `expectedVersion`) → return it; on failure return `Optional.empty()` (original Redis key is **not** deleted) |
| `version > CURRENT` | Return `Optional.empty()` (skip to prevent data loss on rolling deployments) |

**Migration chain** (`CartSchemaMigrationService`):
- Auto-discovers all `CartMigrationStep` `@Component` beans via Spring DI.
- Each step declares `fromVersion()` and transforms `RedisCart` to the next version.
- Walks the chain in memory step-by-step until `schemaVersion == CURRENT`.
- `CartMigrationException` is thrown if any step is missing in the chain.
- The cart is saved **once** (via CAS) after the full chain completes — no intermediate saves.

To add a new schema version: implement `CartMigrationStep`, annotate it `@Component`, set `fromVersion()` to the previous version, and bump `CURRENT_SCHEMA_VERSION`.

## Checkout Executor

Checkout validation runs in a dedicated thread pool (`checkoutExecutor` bean, `CheckoutExecutorConfig`):

| Setting | Value |
|---|---|
| Core pool size | 4 |
| Max pool size | 16 |
| Queue capacity | 100 |
| Thread prefix | `checkout-` |
| Rejection policy | `AbortPolicy` (throws `RejectedExecutionException`) |

`AbortPolicy` is intentional — `CallerRunsPolicy` would occupy the HTTP request thread for the full checkout validation duration under load. `CheckoutService` catches `RejectedExecutionException` at the `supplyAsync()` call site and returns HTTP 503. Separate metrics are emitted for initiation (`cart.checkout.initiate.executor_rejected`) and confirmation (`cart.checkout.confirm.executor_rejected`) rejection events.

The executor wraps threads with `MdcTaskDecorator` to propagate MDC fields (request IDs, trace IDs) into async threads.

### Checkout Safety Rules

**`confirmCheckout` — CAS save before Kafka publish:**
The basket is removed from the in-memory cart and persisted via CAS _before_ `CheckoutConfirmedEvent` is published to Kafka. If the CAS fails (concurrent write → 409), no event is emitted. If the CAS succeeds but Kafka publish fails, the basket is gone and the client receives 503 — an acknowledged trade-off until an outbox pattern is introduced.

**`initiateCheckout` — conditional save (dirty flag):**
The cart is saved to Redis only when the in-memory state actually changed during the request (new address set, delivery quote updated/cleared, scheduled slot reset to ASAP). If nothing changed (cached quote still valid, same address), `cart.touch()` and the CAS save are skipped entirely. The save is placed _before_ the blocking-issue check so legitimate state corrections persist even on a blocked checkout response.

**Delivery quote validity thresholds:**
- `isQuoteValid()` (`> now`) — used by the regular `resolve()` path for cart browsing and mutations.
- `isQuoteValidForCheckout()` (`> now + 30s`) — used exclusively by `resolveForCheckout()`. A quote with less than 30 seconds remaining is treated as missing and re-fetched, ensuring the quote survives the Kafka → order-service → delivery-service fee-lock chain. There is no separate expiry guard in `confirmCheckout`; this method is the single enforcement point.

### `.get()` Gate Timeout

Both `initiateCheckout` and `confirmCheckout` submit a **single future** — `cartValidationService.validateForCheckout()` — which handles projection validation, the unified delivery checkout-quote HTTP call, and promotion evaluation in one pass:

| Future | `.get()` timeout | Rationale |
|---|---|---|
| `cartValidationService.validateForCheckout()` | **8s** | Covers sequential DB queries (Hikari 3.5s + per-query hints) + unified delivery checkout-quote HTTP + promotion HTTP (500ms) under load |

`TimeoutException` → future cancelled → HTTP 503.

## Observability

### Distributed Tracing

The OTel Java Agent v2.9.0 is bundled in the Docker image and activated unconditionally via `JAVA_TOOL_OPTIONS`:

```dockerfile
COPY otel/opentelemetry-javaagent-2.9.0.jar /opt/otel/opentelemetry-javaagent.jar
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/opentelemetry-javaagent.jar"
```

No pom.xml dependency is required. The agent auto-instruments Spring MVC, Spring Data, Spring Kafka, and WebClient without code changes.

**Trace pipeline:**

```
cart-service (OTel agent)
  → OTel Collector (gRPC :4317)
  → Tempo
  → Grafana (trace explorer)
```

**OTel environment variables** (injected via `docker-compose.yaml` / `k8s/deployment.yaml`):

| Variable | Value |
|---|---|
| `OTEL_SERVICE_NAME` | `cart-service` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://otel-collector:4317` |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` |
| `OTEL_TRACES_EXPORTER` | `otlp` |
| `OTEL_METRICS_EXPORTER` | `none` (metrics go via Prometheus, not OTel) |
| `OTEL_LOGS_EXPORTER` | `none` (logs go via logback, not OTel) |
| `OTEL_PROPAGATORS` | `tracecontext,baggage` (W3C) |
| `OTEL_RESOURCE_ATTRIBUTES` | `deployment.environment=local` |

W3C `traceparent` headers are propagated automatically to downstream HTTP calls (promotion-service, delivery-service) by the agent.

### MDC / Structured Logging

Three thread contexts each inject MDC fields independently:

| Context | Mechanism | MDC fields set |
|---|---|---|
| HTTP requests | `MdcRequestFilter` (servlet filter) | `requestId`, `userId`, `method`, `path` |
| Kafka consumers | `MdcKafkaRecordInterceptor` (`RecordInterceptor<K,V>`) | `kafkaOffset`, `kafkaTopic`, `kafkaPartition` |
| Checkout async threads | `MdcTaskDecorator` (on `checkoutExecutor`) | copies full MDC snapshot from submitting thread |

The OTel agent also injects `traceId` and `spanId` into MDC automatically, so every log line includes trace context when a span is active.

### Metrics (Prometheus)

Micrometer exposes metrics at `/actuator/prometheus`. Prometheus scrapes this endpoint.

**Key application metrics:**

| Metric | Tags | Description |
|---|---|---|
| `cart.items.added` | `store_id` | Item added to cart |
| `cart.items.removed` | `store_id` | Item removed from cart |
| `cart.basket.cleared` | `store_id` | Full basket cleared |
| `cart.items.batch_upserted` | `store_id` | Batch upsert call |
| `cart.promo.claimed` | `store_id`, `result` | Promo code claim attempt |
| `cart.checkout.initiated` | `store_id` | Initiation passed all checks |
| `cart.checkout.initiate_blocked` | `store_id`, `scope` | Initiation blocked by issue |
| `cart.checkout.initiate.cart_not_found` | — | No cart found at initiation |
| `cart.checkout.initiate.basket_not_found` | — | No basket found at initiation |
| `cart.checkout.initiate.basket_empty` | — | Empty basket at initiation |
| `cart.checkout.initiate.execution_error` | `store_id` | Unexpected error in async initiate phase |
| `cart.checkout.initiate.executor_rejected` | `store_id` | Thread pool saturated at initiation |
| `cart.checkout.initiate.duration` | (histogram) | Wall-clock time for full initiateCheckout round-trip |
| `cart.checkout.confirmed` | `store_id` | Checkout confirmed |
| `cart.checkout.rejected` | `store_id`, `scope` | Checkout rejected by blocking issue |
| `cart.checkout.confirm.execution_error` | `store_id` | Unexpected error in async confirm phase |
| `cart.checkout.confirm.executor_rejected` | `store_id` | Thread pool saturated at confirmation |
| `cart.checkout.confirm.duration` | (histogram) | Wall-clock time for full confirmCheckout round-trip |
| `cart.checkout.kafka.publish_failed` | `store_id` | Kafka publish failed after confirm |
| `cart.validation.duration` | `blocking_issues` | Full validation pipeline duration |
| `cart.promotion.degraded` | `reason` | Promotion service unavailable |
| `cart.promotion.call.duration` | (histogram) | Promotion-service HTTP call latency |
| `cart.delivery.call.duration` | `store_id`, `type` | Delivery-service HTTP call latency |
| `cart.delivery.quote.cache_hit` | `store_id` | Delivery quote served from cache |
| `cart.delivery.quote.fetched` | `store_id`, `type` | Remote delivery quote fetched |
| `cart.delivery.quote.fetch_failed` | `store_id`, `type` | Remote delivery quote failed |
| `cart.response.cache.hit` | — | GET cart response served from cache |
| `cart.response.cache.miss` | — | GET cart response rebuilt |
| `cart.get_basket.cart_not_found` | — | No cart found on GET basket |
| `cart.get_basket.basket_not_found` | — | No basket found on GET basket |
| `cart.get_basket.empty` | — | Empty basket on GET basket |

**Known metric gaps** (not yet instrumented):
- Projection staleness health indicator (all three projection tables have `projectionUpdatedAt` / `updatedAt` but no `HealthIndicator` queries them)

### Local Observability Stack

Started via `docker-compose.yaml`:

| Service | Image | Port | Role |
|---|---|---|---|
| `otel-collector` | `otel/opentelemetry-collector-contrib` | `4317` (gRPC), `4318` (HTTP) | Receives spans from agent, forwards to Tempo |
| `tempo` | `grafana/tempo:2.6.1` | `3200` | Trace storage, queried by Grafana |
| `prometheus` | `prom/prometheus:v2.55.0` | `9090` | Scrapes `/actuator/prometheus` |
| `grafana` | `grafana/grafana:11.3.0` | `3000` | Dashboards (traces via Tempo, metrics via Prometheus) |

Grafana datasources and dashboards are provisioned from `docker/grafana/provisioning/` and `docker/grafana/dashboards/`.

## Project Structure
```text
src/main/java/com/sebet/cartservice
  cart/
    controller/
    service/
    repository/
    config/
    delivery/
    dto/
    exception/          ← CartVersionConflictException (HTTP 409)
    migration/          ← CartMigrationStep chain (schema versioning)
    model/
    mapper/
    metrics/            ← CartMetrics (Micrometer counters + timers)
    promotion/
    validation/
    product|inventory|store/
      projection/
      projection/event/
src/main/resources
  docker/               ← otel-collector, tempo, prometheus, grafana configs
  otel/                 ← opentelemetry-javaagent-2.9.0.jar
src/test/java
```
