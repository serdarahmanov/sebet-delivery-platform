# Testing Strategy

## Current Tests

The project currently has order-service tests covering:

- Redis key generation.
- MVC interceptor tests for `X-User-Id`, `X-Store-Id`, and `X-Internal-Key`.
- Internal-auth secret startup validation for every environment.
- Spring context test with PostgreSQL, Redis, and Kafka Testcontainers.
- JPA repository tests against PostgreSQL Testcontainers.
- Order creation service integration tests.
- Checkout event mapper unit tests.
- Checkout event handler lock, validation, and idempotency unit tests.
- Checkout processed-event reservation and expired-lease reclaim integration tests.
- Checkout Kafka listener and retry/DLT integration tests against real brokers.
- Cache-eviction projection consumer unit tests for deliberate C2 eviction events, strategy registry dispatch, unknown cache name handling, and Redis failure pause behavior.
- Redis recovery scheduler unit tests for pause detection, recovery resume, connection failure, and timeout handling.
- `CustomerOrderQueryService` unit tests covering all 10 read methods: cache hit, DB fallback, ownership denial, timeline building, order number format, and batch item queries.
- Customer active-order reads filter out stale C1 entries whose C2 snapshot belongs to another user.
- Customer status reads fallback to PostgreSQL when C4 has an invalid status value.
- `CustomerOrderQueryService` integration tests against real Postgres and Redis containers covering the full read path: history feed, active orders, smart router, delivered/cancelled flows, ownership checks, and C4 expiry fallback.
- `StoreOrderQueryService` unit tests covering history, active orders, scheduled orders, detail mapping, proposal merge, status cache reads, stale Redis fallback, DB fallback, and wrong-store ownership hiding.
- `OrderLifecycleService` unit tests for store `accept`, `reject`, `ready`, and `cancel` transitions, invalid transitions, wrong-store ownership hiding, and invalid UUID handling.
- `OrderLifecycleRedisUpdater` unit tests for status updates, `PACKED` timeline append behavior, duplicate timeline prevention, cancellation hot-view cleanup, and store-cancel grouped fallback routing.
- `CancelledOrderHotViewsCacheEvictionStrategy` unit tests for C1/C1b/C2/C3/C4/C6 key computation and atomic script execution.
- `StoreOrderLifecycleService` unit tests for full `OUT_OF_STOCK` reject validation, rejection metadata persistence, store cancel reason mapping, cancel metadata persistence, idempotent command execution, and replay-triggered Redis eviction.
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
- internal API key validation and fail-fast behavior when the secret is blank
- checkout event mapping
- checkout event consumer delegation
- checkout event handler Redis lock, validation, and idempotency behavior
- checkout processed-event insert-first reservation, `IN_PROGRESS` conflict, completion, release, and expired-lease reclaim behavior
- checkout Kafka listener integration
- durable order repository behavior
- optimistic locking and per-order product uniqueness
- order creation behavior
- Redis hot-view writes for ASAP and scheduled order creation
- Redis store membership TTL refresh
- Redis order status customer/store ownership serialization
- store accept/reject/ready/cancel lifecycle transitions, including idempotent store cancel replay
- store read service mapping and ownership checks
- customer active-order wrong-owner snapshot filtering
- `OUT_OF_STOCK` rejection validation against persisted order items
- invalid lifecycle transition handling
- lifecycle Redis status/timeline/cancellation/delivery updates
- driver pickup/arrive/complete lifecycle transitions
- driver delivery detail and decline assignment behavior
- stale C2 driver mismatch fallback to DB before returning `DRIVER_NOT_ASSIGNED`
- internal driver assign/replace/unassign behavior
- internal driver assignment idempotency and C2 eviction fallback behavior
- driver decline idempotency and C2 eviction fallback behavior
- cache-eviction projection consumer strategy dispatch, unknown cacheName skipping, and multi-strategy routing
- `OrderCacheEvictionRequested` outbox event payload writing
- C2 eviction coordinator direct-delete, Redis-failure fallback-event, fallback-failure, non-Redis failure propagation, and generic strategy routing behavior
- idempotent command reservation, `IN_PROGRESS` conflict, completed replay, request-conflict, expired-lease reclaim, and failure cleanup behavior
- concurrent checkout order creation for the same `cartId` against PostgreSQL, verifying the unique `orders.cart_id` constraint resolves to one order
- checkout processed-event retry-window validation, verifying the dedicated `IN_PROGRESS` retry window covers the processed-event lease
- `DriverAssignmentDeclined` outbox event payload writing
- `DriverAssigned`, `DriverReplaced`, and `DriverUnassigned` outbox event payload writing
- driver ownership verification (`DRIVER_NOT_ASSIGNED`)
- verification code generation, C7 write, and DB metadata persistence
- verification code validation against C7 with DB fallback
- `VerificationCodeNotFoundException` on missing code in both stores
- promo-service `update-after-proposal` application, including final repriced item replacement, proposal `APPLIED` marking, idempotent replay Redis refresh, C2/C8/C4/C6 Redis updater behavior, and `OrderProposalApplied` outbox event payload writing

## Unit Tests To Add

Add unit tests for:

- remaining status transition rules
- remaining cancellation rules
- proposal resolution rules
- Redis repository serialization/deserialization
- active-order removal Lua behavior
- lock release Lua behavior
- customer write service behavior when implemented
- remaining proposal cancellation behavior when implemented: store `cancel-active-proposal` and internal `cancel-proposal-and-order`
- `OrderLifecycleRedisUpdater` for `OUT_FOR_DELIVERY`, `ARRIVED`, and `DELIVERED` transitions

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
- initial order creation for ASAP and scheduled orders
- duplicate cart id handling in order creation
- checkout event handler mapping, lock handling, validation, processed-event idempotency, and duplicate-cart behavior
- checkout processed-event reservation, completion, release, and expired-lease reclaim against real PostgreSQL transactions
- checkout event handler Redis hot-view initialization and recovery behavior from current database state
- Kafka listener integration against a real broker
- Kafka retry/DLT behavior
- checkout event consumption with Redis lock support
- idempotent command reservation visibility, same-key in-progress conflict, completed replay, and expired reservation reclaim against real PostgreSQL transactions

Additional integration tests to add:

- store-facing read methods with real Postgres and Redis
- store-facing lifecycle write methods with real Postgres and Redis
- driver lifecycle write methods with real Postgres and Redis (pickup → arrive → complete flow)
- verification code DB fallback when C7 has expired
- remaining order status transition service methods
- Redis repositories
- WebSocket/STOMP subscriptions

## Release Validation

Before release:

```powershell
.\mvnw.cmd package
```
