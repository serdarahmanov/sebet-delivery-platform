# Implementation Gaps

This file tracks behavior that is designed in the docs and DTOs but not implemented in code yet.

## Service Layer

Many controller methods still throw:

```text
UnsupportedOperationException("Not implemented yet")
```

Implemented:

- `OrderCreationService` creates durable orders from internal checkout commands.
- Redis hot-view initialization for created orders from current database state.
- `DRIVER_ASSIGNED` removed from `OrderStatus` enum; driver assignment is modelled as `driverId` / `driverAssignedAt` metadata fields on the order.
- `CustomerOrderQueryService` implements all 10 customer-facing GET methods: history feed, active orders list, active order detail, scheduled detail, cancelled detail, smart router, status, tracking, verification code, and proposed changes.
- Customer single-order read ownership verification returns 404 for both not-found and wrong-user responses.
- Customer active-order list reads skip stale C1 entries whose C2 snapshot belongs to another user.
- `StoreOrderQueryService` implements all 5 store-facing GET methods: history feed, active orders list, scheduled orders list, order detail, and status.
- Store read ownership verification returns 404 for both not-found and wrong-store responses.
- `OrderLifecycleService` implements store lifecycle transitions:
  - `PENDING -> CONFIRMED` through `POST /api/v1/store/orders/{orderId}/accept`
  - `PENDING -> CANCELLED` through `POST /api/v1/store/orders/{orderId}/reject`
  - `CONFIRMED -> READY_FOR_PICKUP` through `POST /api/v1/store/orders/{orderId}/ready`
- Store lifecycle writes use `orders.version` optimistic locking so concurrent lifecycle updates cannot both commit.
- Store `OUT_OF_STOCK` rejection validation verifies that item details are present, belong to the order, match requested quantity/unit, and provide a valid partial-stock quantity when applicable.
- Store `OUT_OF_STOCK` rejection validation rejects duplicate product ids and null list elements.
- Store rejection metadata is persisted into `order_status_history.metadata_json`.
- `OrderLifecycleService` implements driver lifecycle transitions:
  - `READY_FOR_PICKUP -> OUT_FOR_DELIVERY` through `POST /api/v1/driver/orders/{orderId}/pickup`
  - `OUT_FOR_DELIVERY -> ARRIVED` through `POST /api/v1/driver/orders/{orderId}/arrive`
  - `ARRIVED -> DELIVERED` through `POST /api/v1/driver/orders/{orderId}/complete`
- `DriverOrderLifecycleService` implements driver delivery detail and assignment decline:
  - `GET /api/v1/driver/orders/{orderId}` returns assigned delivery detail from C2 + C4, with DB fallback.
  - `POST /api/v1/driver/orders/{orderId}/decline` clears `driverId` / `driverAssignedAt` before pickup, writes `DriverAssignmentDeclined` and the idempotent command record in the same database transaction, then tries to evict C2 after commit.
- `InternalDriverAssignmentService` implements internal driver assignment:
  - `POST /api/v1/internal/orders/{orderId}/assign-driver` assigns a driver, idempotently returns the same driver assignment, or replaces a different driver.
  - `POST /api/v1/internal/orders/{orderId}/unassign-driver` clears the assigned driver on non-terminal orders.
  - Assignment writes persist the order change, `DriverAssigned`, `DriverReplaced`, or `DriverUnassigned` outbox event, and idempotent command record in the same database transaction, then try to evict C2 Redis after commit.
- `OrderCacheEvictionService` handles direct C2 eviction and fallback event recording:
  - direct Redis eviction success returns normally
  - Redis connection failure or command timeout writes `OrderCacheEvictionRequested` and lets the endpoint return success
  - non-Redis runtime failures propagate normally
  - fallback event write failure raises `503 CACHE_INVALIDATION_FAILED`
- `OrderCacheEvictionProjectionConsumer` consumes deliberate cache eviction requests from `order-events`:
  - skips unsupported event types
  - ignores general driver assignment events
  - dispatches `OrderCacheEvictionRequested` events to the matching `CacheEvictionStrategy` by `data.cacheName`; unknown cache names are skipped with a warning
  - uses MANUAL ack mode; pauses container on `RedisConnectionFailureException` or `QueryTimeoutException` without committing the offset
  - `RedisRecoveryScheduler` probes Redis every 10 seconds (500 ms timeout) and resumes the container when Redis responds
- `CacheEvictionStrategy` interface allows adding eviction support for any cache key by implementing a single `@Component`; `C2CacheEvictionStrategy` is the current registered implementation
- Driver transitions verify `driverId` ownership, returning `403 DRIVER_NOT_ASSIGNED` on mismatch.
- `arrive` generates a 2-digit verification code written to C7 (30-min TTL) and persisted to `order_status_history.metadata_json` as a permanent fallback.
- `complete` validates the submitted code against C7, falling back to `order_status_history` if C7 has expired. Wrong code returns `400`; code not found in either store returns `404 VERIFICATION_CODE_NOT_FOUND`.
- `delivered_at` is set to the same `changedAt` instant as the history record, preventing split-timestamp inconsistency.
- Redis lifecycle transition updates extended: C4 status, C6 timeline (`PACKED`, `ON_THE_WAY`, `ARRIVED`, `DELIVERED`), cancellation hot-view cleanup, and delivered hot-view cleanup (C1, C1b, C2, C3, C7 cleared; C4 and C6 retained).

Pending:

- remaining store write methods: `cancel`, `propose-changes`
- remaining lifecycle transitions: customer cancel, scheduled activation, proposal resolution, and delivery cancellation paths
- proposals write path (`respond-to-changes`)
- scheduled order update write path
- hot-view repair/eviction policy for non-checkout write paths beyond the implemented store and driver assignment/lifecycle transitions

## Database

Implemented:

- JPA entities for `orders`, `order_items`, and `order_status_history`
- JPA repositories
- Flyway migration `V1__create_order_tables.sql` covering the base order schema, outbox event table, processed-events idempotency, enum rename, and the extended order/item schema
- Flyway migration `V3__create_idempotent_commands.sql` covering REST write idempotency records
- unique `cart_id` idempotency constraint
- unique `(action, idempotency_key)` idempotent command constraint
- optimistic lock `orders.version` column
- unique `(order_id, product_id)` order item constraint
- repository tests

Pending:

- durable proposal/refund/verification fields if required by later workflows
- further indexes based on actual query patterns

## Kafka

Implemented:

- checkout confirmed envelope and payload DTOs
- checkout event to order creation command mapper
- raw-string checkout event consumer
- checkout event handler validation and processed-event idempotency
- real-broker Kafka listener integration tests
- checkout event retry and DLT handling
- checkout DLT topic startup validation
- Kafka retry/DLT integration coverage for retryable, non-retryable, malformed payload, partition/key preservation, and DLT publish failure paths
- Redis lock integration for checkout event handling
- Redis hot-view initialization for created orders from current database state
- order-created, lifecycle, and driver-assignment event outbox writes for Debezium publishing
- cache-eviction projection consumer for deliberate C2 Redis eviction requests from `order-events`

Pending:

- delivery arrival consumer
- Debezium connector deployment/runtime wiring

## Background Jobs

Pending:

- scheduled order transition job
- proposal timeout job
- store response timeout job if needed

## WebSocket

Pending:

- STOMP broker configuration
- SockJS fallback if required
- topic naming
- customer/store push payloads
- authorization rules for subscriptions

## Driver Endpoints

Implemented:

- `POST /{orderId}/pickup` - `READY_FOR_PICKUP -> OUT_FOR_DELIVERY`
- `POST /{orderId}/arrive` - `OUT_FOR_DELIVERY -> ARRIVED`; generates verification code into C7 and `order_status_history.metadata_json`
- `POST /{orderId}/complete` - `ARRIVED -> DELIVERED`; validates code from C7 with DB fallback
- `GET  /{orderId}` - delivery detail
- `POST /{orderId}/decline` - unassigns driver; valid before `OUT_FOR_DELIVERY`

## Internal Endpoints

Implemented:

- `POST /{orderId}/assign-driver` - sets `driverId` and `driverAssignedAt`
- `POST /{orderId}/unassign-driver` - clears `driverId`; valid on any non-terminal status

Pending:

- `POST /{orderId}/system-cancel` - system-initiated cancellation
- `POST /{orderId}/activate-scheduled` - `SCHEDULED -> PENDING`
- `POST /{orderId}/cancel-proposal` - `AWAITING_CUSTOMER_RESPONSE -> CANCELLED`

## Error Handling

Implemented:

- `GlobalExceptionHandler` (`@RestControllerAdvice`) maps common exceptions to consistent HTTP responses.
- `ErrorResponse` record (`shared/exception/`) with `code`, `message`, and `timestamp` fields.
- Checkout envelope validation in `CheckoutConfirmedHandler`.
- Order creation invariant validation in `CreateOrderCommand`.
- `deliveryAddressJson` JSON parse validation in `OrderCreationService` before DB write.
- `InternalAuthInterceptor` fails startup in every environment when `order-service.internal.secret` is blank.
- `ORDER_NOT_FOUND` (404), raised by `OrderNotFoundException`, is used for both not-found and wrong-owner responses.
- `ORDER_INVALID_TRANSITION` (409), raised by `OrderInvalidTransitionException`, is used for invalid lifecycle transitions.
- `OptimisticLockingFailureException` is mapped to `ORDER_INVALID_TRANSITION` (409) for stale concurrent lifecycle writes.

- `DriverNotAssignedException` maps to `403 DRIVER_NOT_ASSIGNED`.
- `VerificationCodeNotFoundException` maps to `404 VERIFICATION_CODE_NOT_FOUND`.
- `IdempotencyKeyConflictException` maps to `409 IDEMPOTENCY_KEY_CONFLICT`.
- `CacheInvalidationFailedException` maps to `503 CACHE_INVALIDATION_FAILED`.

Pending:

- `ORDER_NOT_CANCELLABLE`, `PROPOSAL_EXPIRED`, and other domain-specific codes as write paths are implemented

## Deployment

Pending:

- Dockerfile
- compose integration
- Kubernetes manifest or Helm chart
- health probes
