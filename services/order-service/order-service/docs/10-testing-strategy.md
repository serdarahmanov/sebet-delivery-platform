# Testing Strategy

## Current Tests

The project currently has 135 tests covering:

- Redis key generation.
- MVC interceptor tests for `X-User-Id` and `X-Store-Id`.
- Spring context test with PostgreSQL, Redis, and Kafka Testcontainers.
- JPA repository tests against PostgreSQL Testcontainers.
- Order creation service integration tests.
- Checkout event mapper unit tests.
- Checkout event processor lock unit tests.
- Checkout Kafka listener and retry/DLT integration tests against real brokers.
- `CustomerOrderQueryService` unit tests covering all 10 read methods: cache hit, DB fallback, ownership denial, timeline building, order number format, and batch item queries.
- Customer status reads fallback to PostgreSQL when C4 has an invalid status value.
- `CustomerOrderQueryService` integration tests against real Postgres and Redis containers covering the full read path: history feed, active orders, smart router, delivered/cancelled flows, ownership checks, and C4 expiry fallback.
- `StoreOrderQueryService` unit tests covering history, active orders, scheduled orders, detail mapping, proposal merge, status cache reads, stale Redis fallback, DB fallback, and wrong-store ownership hiding.
- `OrderLifecycleService` unit tests for store `accept`, `reject`, and `ready` transitions, invalid transitions, wrong-store ownership hiding, and invalid UUID handling.
- `OrderLifecycleRedisUpdater` unit tests for status updates, `PACKED` timeline append behavior, duplicate timeline prevention, and cancellation hot-view cleanup.
- `StoreOrderLifecycleService` unit tests for full `OUT_OF_STOCK` reject validation and rejection metadata persistence.
- repository tests for optimistic locking and per-order product uniqueness.
- Redis store membership repository tests for active and scheduled TTL refresh.
- Redis order status repository tests for customer/store ownership serialization.

The Spring context test uses Testcontainers to start PostgreSQL, Redis, and
Kafka, then boots the application with the `test` Spring profile.

## Test Commands

Run all tests:

```powershell
.\mvnw.cmd test
```

Run a clean full verification:

```powershell
.\mvnw.cmd clean test
```

On Windows, the `windows-testcontainers` Maven profile is activated
automatically. It configures Surefire with the Docker Desktop named pipe and
Docker API version required by Testcontainers, so no manual environment setup is
needed for the normal test command.

Run one test class:

```powershell
.\mvnw.cmd test -Dtest=OrderServiceApplicationTests
```

Compile without running tests:

```powershell
.\mvnw.cmd compile
```

## Implemented Baseline Tests

- Redis key generation
- required `X-User-Id`
- required `X-Store-Id`
- checkout event mapping
- checkout event consumer delegation
- checkout event processor Redis lock behavior
- checkout Kafka listener integration
- durable order repository behavior
- optimistic locking and per-order product uniqueness
- order creation behavior
- Redis hot-view writes for immediate and scheduled order creation
- Redis store membership TTL refresh
- Redis order status customer/store ownership serialization
- store accept/reject/ready lifecycle transitions
- store read service mapping and ownership checks
- `OUT_OF_STOCK` rejection validation against persisted order items
- invalid lifecycle transition handling
- lifecycle Redis status/timeline/cancellation updates

## Unit Tests To Add

Add unit tests for:

- remaining status transition rules
- remaining cancellation rules
- proposal resolution rules
- Redis repository serialization/deserialization
- active-order removal Lua behavior
- lock release Lua behavior
- customer write service behavior when implemented
- driver/internal lifecycle service behavior when implemented

## Controller Tests To Add

Add controller tests for:

- request validation
- route mappings
- status code mappings, especially `404`, `409`, and `501`

## Integration Tests To Add

Implemented integration coverage:

- Flyway migration application
- JPA order/item/status-history persistence
- cart id uniqueness
- item line-number ordering
- initial order creation for immediate and scheduled orders
- duplicate cart id handling in order creation
- checkout event processor mapping, lock handling, and duplicate-path behavior
- checkout event processor Redis hot-view initialization and recovery behavior from current database state
- Kafka listener integration against a real broker
- Kafka retry/DLT behavior
- checkout event consumption with Redis lock support

Additional integration tests to add:

- store-facing read methods with real Postgres and Redis
- store-facing lifecycle write methods with real Postgres and Redis
- remaining order status transition service methods
- Redis repositories
- WebSocket/STOMP subscriptions

## Release Validation

Before release:

```powershell
.\mvnw.cmd package
```
