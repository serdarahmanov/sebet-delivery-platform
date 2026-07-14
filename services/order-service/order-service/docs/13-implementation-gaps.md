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
  - `CONFIRMED` or `AWAITING_CUSTOMER_RESPONSE` -> `CANCELLED` through `POST /api/v1/store/orders/{orderId}/cancel`
- Store lifecycle writes use `orders.version` optimistic locking so concurrent lifecycle updates cannot both commit.
- Store `OUT_OF_STOCK` rejection validation verifies that item details are present, belong to the order, match requested quantity/unit, and provide a valid partial-stock quantity when applicable.
- Store `OUT_OF_STOCK` rejection validation rejects duplicate product ids and null list elements.
- Store rejection and cancellation metadata is persisted into `order_status_history.metadata_json`.
- Store `/cancel` requires `Idempotency-Key`; the key is reserved as `IN_PROGRESS` before business execution, the owning request stores the completed response after the order update and lifecycle outbox event, and idempotent replay reruns Redis cleanup.
- `OrderLifecycleService` implements driver lifecycle transitions:
  - `READY_FOR_PICKUP -> OUT_FOR_DELIVERY` through `POST /api/v1/driver/orders/{orderId}/pickup`
  - `OUT_FOR_DELIVERY -> ARRIVED` through `POST /api/v1/driver/orders/{orderId}/arrive`
  - `ARRIVED -> DELIVERED` through `POST /api/v1/driver/orders/{orderId}/complete`
- `OrderLifecycleService` implements internal scheduled activation:
  - manual admin/support `SCHEDULED -> PENDING` through `POST /api/v1/internal/orders/{orderId}/activate-scheduled`
  - activation reserves the idempotency key first, then persists status history, emits `OrderActivated`, and stores the completed idempotent response
  - after commit and on idempotent replay, activation atomically moves Redis hot views from Cache 1c to Cache 1 and Cache 1b and updates Cache 4 through a Lua script
  - recoverable Redis activation-update failures write an `OrderCacheEvictionRequested` event for `SCHEDULED_ACTIVATION_HOT_VIEWS`
- `OrderLifecycleService` implements internal system cancellation:
  - automated system `PENDING`, `CONFIRMED`, `AWAITING_CUSTOMER_RESPONSE`, `SCHEDULED`, or `READY_FOR_PICKUP` -> `CANCELLED` through `POST /api/v1/internal/orders/{orderId}/system-cancel`
  - admin override cancellation for any non-`DELIVERED` order through `POST /api/v1/internal/orders/{orderId}/admin-cancel`
  - allowed reasons are `PAYMENT_FAILED`, `NO_RIDERS_AVAILABLE`, `STORE_RESPONSE_TIMEOUT`, `AWAITING_CUSTOMER_RESPONSE_TIMEOUT`, and `SYSTEM_ERROR`
  - cancellation reserves the idempotency key first, then persists status history, emits the lifecycle outbox event, and stores the completed idempotent response
  - after commit and on idempotent replay, Redis cleanup uses `CANCELLED_ORDER_HOT_VIEWS` to remove C1/C1b membership and delete C2/C3/C4/C6 through the recoverable eviction fallback pattern
- `DriverOrderLifecycleService` implements driver delivery detail and assignment decline:
  - `GET /api/v1/driver/orders/{orderId}` returns assigned delivery detail from C2 + C4, with DB fallback.
  - `POST /api/v1/driver/orders/{orderId}/decline` reserves the idempotency key, clears `driverId` / `driverAssignedAt` before pickup, writes `DriverAssignmentDeclined`, stores the completed idempotent response, then tries to evict C2 after commit.
- `InternalDriverAssignmentService` implements internal driver assignment:
  - `POST /api/v1/internal/orders/{orderId}/assign-driver` assigns a driver, idempotently returns the same driver assignment, or replaces a different driver.
  - `POST /api/v1/internal/orders/{orderId}/unassign-driver` clears the assigned driver on non-terminal orders.
  - Assignment writes reserve the idempotency key, persist the order change, write `DriverAssigned`, `DriverReplaced`, or `DriverUnassigned` outbox event, store the completed idempotent response, then try to evict C2 Redis after commit.
- `OrderCacheEvictionService` handles direct Redis hot-view eviction and fallback event recording:
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
- `CacheEvictionStrategy` interface allows adding eviction support for any cache key or grouped hot-view cleanup by implementing a single `@Component`; current implementations are `C2CacheEvictionStrategy`, `CancelledOrderHotViewsCacheEvictionStrategy`, `ProposeChangesHotViewsEvictionStrategy`, `CancelActiveProposalHotViewsEvictionStrategy`, and `RespondAcceptHotViewsEvictionStrategy`
- Driver transitions verify `driverId` ownership, returning `403 DRIVER_NOT_ASSIGNED` on mismatch.
- `arrive` generates a 2-digit verification code written to C7 (30-min TTL) and persisted to `order_status_history.metadata_json` as a permanent fallback.
- `complete` validates the submitted code against C7, falling back to `order_status_history` if C7 has expired. Wrong code returns `400`; code not found in either store returns `404 VERIFICATION_CODE_NOT_FOUND`.
- `delivered_at` is set to the same `changedAt` instant as the history record, preventing split-timestamp inconsistency.
- Redis lifecycle transition updates extended: C4 status, C6 timeline (`PACKED`, `ON_THE_WAY`, `ARRIVED`, `DELIVERED`), cancellation hot-view cleanup, and delivered hot-view cleanup (C1, C1b, C2, C3, C7 cleared; C4 and C6 retained).
- Store `/cancel` uses `CANCELLED_ORDER_HOT_VIEWS` after commit and on idempotent replay. The strategy removes C1/C1b memberships and deletes C2, C3, C4, and C6 in one Redis Lua script. Recoverable Redis failures or timeouts write one `OrderCacheEvictionRequested` event with all target `cacheKeys`; non-Redis failures propagate.
- `StoreOrderLifecycleService.proposeChanges` and `OrderLifecycleService.storeProposeChangesWithoutRedisUpdate` / `updateProposeChangesRedisViews` implement `POST /api/v1/store/orders/{orderId}/propose-changes`:
  - validates that `productName`, `unit`, and `requestedQuantity` in each proposed item match the persisted order item
  - reserves the idempotency key, transitions `CONFIRMED -> AWAITING_CUSTOMER_RESPONSE`, appends status history, persists the proposal record and `OrderProposedToCustomer` outbox event, and stores the completed idempotent response
  - atomically updates C8, C4, and C6 via `proposeChangesRedisUpdateScript` after commit; recoverable Redis failures fall back to `requestEvictionAfterUpdateFailure` with `PROPOSE_CHANGES_HOT_VIEWS`, which writes one `OrderCacheEvictionRequested` event without attempting direct eviction
  - `OrderProposeChangesRedisUpdater` builds and executes the Lua script; `ProposeChangesHotViewsEvictionStrategy` handles grouped eviction of C8 + C4 + C6 for the consumer path
- `InternalOrderLifecycleService.cancelActiveProposal` implements `POST /api/v1/internal/orders/{orderId}/cancel-active-proposal`:
  - transitions `AWAITING_CUSTOMER_RESPONSE -> CONFIRMED`
  - marks the proposal row as `CANCELLED` (retained for audit; not deleted)
  - reserves the idempotency key, appends status history, emits `OrderActiveProposalCancelled`, and stores the completed idempotent response
  - atomically deletes C8, updates C4 to `CONFIRMED`, and removes `AWAITING_CUSTOMER_RESPONSE` entries from C6 via `cancelActiveProposalRedisUpdateScript`; recoverable Redis failures fall back to `CANCEL_ACTIVE_PROPOSAL_HOT_VIEWS`
- `InternalOrderLifecycleService.cancelProposalAndOrder` implements `POST /api/v1/internal/orders/{orderId}/cancel-proposal-and-order`:
  - transitions `AWAITING_CUSTOMER_RESPONSE -> CANCELLED`
  - marks the proposal row as `TIMED_OUT` (retained for audit; not deleted)
  - cancellation reason is fixed to `AWAITING_CUSTOMER_RESPONSE_TIMEOUT`
  - reserves the idempotency key, appends status history, emits `OrderCancelled`, and stores the completed idempotent response
  - after commit and on idempotent replay, Redis cleanup uses `CANCELLED_ORDER_HOT_VIEWS` (C1, C1b, C2, C3, C4, C6, C8) through the recoverable eviction fallback pattern
- Any cancellation applied to an order in `AWAITING_CUSTOMER_RESPONSE` (system-cancel, admin-cancel, store cancel) marks the active proposal row as `SYSTEM_CANCELLED` or `STORE_CANCELLED` within the same database transaction.
- `ProposalStatus` enum tracks the full proposal lifecycle: `ACTIVE`, `ACCEPTED`, `APPLIED`, `REJECTED`, `CANCELLED`, `TIMED_OUT`, `SYSTEM_CANCELLED`, `STORE_CANCELLED`.
- `order_proposals.uq_order_proposals_order_id` unique constraint replaced by a partial unique index (`WHERE status = 'ACTIVE'`) to allow historical proposal rows while enforcing at most one active proposal per order.
- `CustomerOrderLifecycleService.cancelOrder` implements `POST /api/v1/orders/{orderId}/cancel`:
  - `PENDING`, `CONFIRMED`, or `SCHEDULED` -> `CANCELLED`; reason fixed to `USER_REQUESTED`
  - appends status history and emits `OrderCancelled` in one database transaction
  - after commit, Redis cleanup uses `CANCELLED_ORDER_HOT_VIEWS` through the recoverable eviction fallback pattern
- `CustomerOrderLifecycleService.respondToChanges` implements `POST /api/v1/orders/{orderId}/respond-to-changes`:
  - CANCEL_ORDER path: marks active proposal `REJECTED`, transitions `AWAITING_CUSTOMER_RESPONSE -> CANCELLED`, appends history, emits `OrderCancelled`; Redis cleanup via `CANCELLED_ORDER_HOT_VIEWS` (C8 included in that script)
  - ACCEPT_ALL / ACCEPT_WITH_MODIFICATIONS path: marks active proposal `ACCEPTED`, validates item decisions against the active proposal, serializes per-item decisions, emits `OrderProposalAccepted` (full pricing + original items + decisions + promo codes); order status unchanged at `AWAITING_CUSTOMER_RESPONSE` until promo service callback
  - after commit, accept path atomically deletes C8 via `respondAcceptRedisUpdateScript`; recoverable Redis failures fall back to `RESPOND_ACCEPT_HOT_VIEWS`
  - ACCEPT_WITH_MODIFICATIONS requires non-empty `itemDecisions`; 400 returned when empty
  - item decision validation: rejects duplicate productIds, unknown productIds, missing decisions for proposed items, `ACCEPT_PROPOSED_QUANTITY` on fully-out-of-stock items, `REQUEST_CUSTOM_QUANTITY` without `customQuantity` or with quantity exceeding available stock
- `CustomerOrderLifecycleService.updateScheduledOrder` implements `PATCH /api/v1/orders/scheduled/{orderId}`:
  - at least one of `scheduledWindowStart`, `newAddress`, or `phoneNumber` must be non-null; empty body returns 400
  - 409 `MODIFICATION_WINDOW_CLOSED` when `scheduledFor ≤ now + modificationCutoffMinutes` (default 40 min, configurable)
  - `scheduledWindowStart` validation: valid ISO-8601, at least `minLeadTimeMinutes` (default 60) in the future, differs from current by at least `slotIntervalMinutes` (default 15), aligned to 15-min slot boundary, and within store working hours on that day
  - store working hours fetched via `StoreServiceClient` with 3 retries; on failure, falls back to env-variable defaults (08:00-19:00, all days)
  - `newAddress` fields: `label` optional, `street`/`city`/`lat`/`lng` required when object is present
  - after commit, `OrderScheduledUpdateRedisWriter` updates Cache 1c ZSET score (if `scheduledFor` changed) and evicts Cache 2 snapshot (if address or phone changed)
  - emits `OrderScheduledUpdated` outbox event
- `CustomerOrderLifecycleService.activateNow` implements `POST /api/v1/orders/scheduled/{orderId}/activate-now`:
  - `SCHEDULED -> PENDING`; actor = USER, reason = CUSTOMER_REQUESTED_ASAP
  - 409 `ORDER_INVALID_TRANSITION` if order is not `SCHEDULED`
  - after commit, atomically moves Redis hot views from Cache 1c to Cache 1 and Cache 1b via the same Lua script used by internal activation
- `delivery_phone_number` and `delivered_proof_image_url` columns added to the `orders` table (V1 migration, no separate migration since app not yet deployed)
- `phoneNumber` propagated through checkout event (`DeliverySnapshot`) → `CreateOrderCommand` → `OrderEntity` → Cache 2 (`DeliveryAddress`) → all customer and store `DeliveryAddressDto` response fields
- `StoreServiceClient`: `RestTemplate`-based HTTP client, `GET {baseUrl}/api/v1/stores/{storeId}/working-hours`, 3-retry loop, parses env-variable fallback on all failures
- `OrderScheduledUpdateRedisWriter`: removes + re-adds the order in Cache 1c ZSET with the new score if `scheduledFor` changed; deletes Cache 2 snapshot (rebuild-on-next-read) if address or phone changed

Pending:

- promo service callback: `POST /api/v1/internal/orders/{orderId}/update-after-proposal` — applies recalculated totals and transitions `AWAITING_CUSTOMER_RESPONSE -> CONFIRMED`; marks proposal `APPLIED`

## Database

Implemented:

- JPA entities for `orders`, `order_items`, and `order_status_history`
- JPA repositories
- Flyway migration `V1__create_order_tables.sql` covering the base order schema, outbox event table, processed-events idempotency, enum rename, and the extended order/item schema
- Flyway migration `V3__create_idempotent_commands.sql` covering REST write idempotency records
- Flyway migration `V8__strengthen_idempotent_commands.sql` covering idempotent command reservation status, lease ownership, completion timestamps, and cleanup/reclaim indexing
- unique `cart_id` idempotency constraint
- unique `(action, idempotency_key)` idempotent command constraint
- `(status, locked_until)` idempotent command cleanup and expired-lease reclaim index
- optimistic lock `orders.version` column
- unique `(order_id, product_id)` order item constraint
- repository tests

Implemented:

- JPA entity for order proposals (`OrderProposalEntity`).
- Flyway migration `V4__create_order_proposals_table.sql`.
- Unique `order_id` constraint on `order_proposals` (one active proposal per order).

Pending:

- durable refund/verification-code extensions if required by later workflows
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

Not in scope for this service:

- Debezium connector deployment — Debezium runs as an external Kafka Connect
  connector. Order-service has no relay code to implement. See
  `docs/14-debezium-outbox.md` for connector config and the deployment decision.

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
- `POST /{orderId}/activate-scheduled` - `SCHEDULED -> PENDING`
- `POST /{orderId}/system-cancel` - system-initiated cancellation
- `POST /{orderId}/admin-cancel` - admin override cancellation
- `POST /{orderId}/cancel-active-proposal` - cancel active proposal without cancelling the order; marks proposal `CANCELLED`
- `POST /{orderId}/cancel-proposal-and-order` - `AWAITING_CUSTOMER_RESPONSE -> CANCELLED`; marks proposal `TIMED_OUT`

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
- `IdempotencyRequestInProgressException` maps to `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`.
- `CacheInvalidationFailedException` maps to `503 CACHE_INVALIDATION_FAILED`.
- `ScheduledOrderModificationWindowClosedException` maps to `409 MODIFICATION_WINDOW_CLOSED`.
- `InvalidScheduledWindowException` maps to `400 INVALID_SCHEDULED_WINDOW`.

Pending:

- `ORDER_NOT_CANCELLABLE`, `PROPOSAL_EXPIRED`, and other domain-specific codes as write paths are implemented

## Deployment

Pending:

- Dockerfile
- compose integration
- Kubernetes manifest or Helm chart
- health probes
