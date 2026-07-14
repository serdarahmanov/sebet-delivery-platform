# Data Storage

Order-service is designed to use PostgreSQL for durable order history and Redis for hot order views.

## Current State

Implemented:

- Redis key registry.
- Redis DTOs.
- Redis repository classes.
- Redis Lua script beans for lock release and active-set cleanup.
- JPA entities for orders, order items, and order status history.
- JPA entity for Debezium outbox events.
- JPA entity for processed checkout event ids.
- JPA entity for idempotent REST commands.
- JPA entity for order proposals.
- Spring Data JPA repositories.
- Flyway migration `V1__create_order_tables.sql` covering the base order schema, outbox events, processed-events idempotency, enum rename, and the extended order/item schema.
- Flyway migration `V3__create_idempotent_commands.sql` for REST write idempotency.
- Flyway migration `V4__create_order_proposals_table.sql` for durable storage of store-proposed item changes.
- Flyway migration `V8__strengthen_idempotent_commands.sql` for idempotent command status, lease ownership, completion timestamps, and cleanup/reclaim indexes.
- Flyway migration `V9__strengthen_processed_events.sql` for checkout event processing status, lease ownership, completion timestamps, and cleanup/reclaim indexes.
- JSONB mapping for the delivery address snapshot and status metadata.
- Unique `orders.cart_id` idempotency constraint.
- Unique per-order `order_items.line_number` constraint.
- Unique per-order `order_items.product_id` constraint.
- Optimistic locking on `orders.version`.

The delivery verification code (C7) is also persisted to `order_status_history.metadata_json` on the `ARRIVED` history record (`{"code": "07"}`). This serves as a permanent fallback if C7 expires before the driver submits the code.

Pending:

- Durable refund or verification-code extensions if those states require long-term storage beyond current implementations.

## Redis Key Registry

All Redis keys are centralized in:

```text
RedisKeys.java
```

Key patterns:

| Name | Key pattern | Type | Purpose |
|---|---|---|---|
| C1 | `user:active_orders:{userId}` | SET | Active order ids per user |
| C1b | `store:active_orders:{storeId}` | SET | Active order ids per store |
| C1c | `store:scheduled_orders:{storeId}` | ZSET | Scheduled orders per store |
| C2 | `order:{orderId}` | STRING/JSON | Static order snapshot |
| C3 | `order:tracking:{orderId}` | STRING/JSON | Live tracking state |
| C4 | `order:status:{orderId}` | STRING | Current status and owner customer/store ids (`"STATUS\|userId\|storeId"`) |
| C5 | `order:lock:{cartId}` | STRING | Order creation lock |
| C6 | `order:timeline:{orderId}` | LIST/JSON | Customer timeline |
| C7 | `order:verification:{orderId}` | STRING/JSON | Delivery verification code |
| C8 | `order:proposals:{orderId}` | STRING/JSON | Active item-change proposal |

## Redis Rules

- C2 static order snapshot has a 48-hour TTL.
- C1b store active-order membership and C1c store scheduled-order membership have a 48-hour TTL refreshed on add.
- C3 tracking data is intended to be short-lived live state.
- C4 stores `"STATUS|userId|storeId"` so customer and store status reads can verify ownership in a single round-trip, with no C2 lookup needed for the status endpoint.
- C5 lock uses `SET NX EX` with a 30-second TTL.
- C6 timeline is append-only customer-facing progress.
- C7 verification code is short-lived and generated near delivery arrival.
- C8 proposal data exists only while waiting for customer response.

## Lua Scripts

`OrderRedisConfig` registers:

- `releaseLockScript`: deletes `order:lock:{cartId}` only when the stored owner matches the caller.
- `removeActiveOrderScript`: removes an order id from an active SET and deletes the key if the set becomes empty.
- `proposeChangesRedisUpdateScript`: atomically writes the proposal JSON to C8, updates the status value in C4, and appends a timeline entry to C6 in one round-trip.
- `evictCancelledOrderHotViewsScript`: atomically removes C1/C1b memberships and deletes C2, C3, C4, and C6 for cancelled-order hot-view cleanup.

These scripts close common Redis race windows.

## PostgreSQL Schema

The current durable schema stores:

- `orders`: durable order header, optimistic lock version, cart id, customer/store ids, status, schedule fields, totals, discounts, delivery address snapshot, coordinates, cancellation fields, driver assignment fields (`driver_id`, `driver_assigned_at`), and timestamps.
- `order_items`: durable item snapshots with product, quantity, unit, pricing, discount, image URL, and line number.
- `order_status_history`: append-style status transition records.
- `outbox_event`: append-only order business events for Debezium publication.
- `processed_events`: checkout event processing ledger with `IN_PROGRESS` reservations and `COMPLETED` dedup records.
- `idempotent_commands`: REST write idempotency reservations and completed responses keyed by action and `Idempotency-Key`.

Important constraints and indexes:

- `orders.id` primary key.
- `orders.cart_id` unique index for checkout idempotency.
- `orders.version` optimistic lock column used by JPA lifecycle writes.
- `order_items.order_id` foreign key to `orders.id` with cascade delete.
- `order_items(order_id, line_number)` unique index.
- `order_items(order_id, product_id)` unique index.
- `order_status_history.order_id` foreign key to `orders.id` with cascade delete.
- customer/store history indexes on `(customer_id, created_at desc)` and `(store_id, created_at desc)`.
- `outbox_event.id` primary key used as the event id.
- `outbox_event.event_key` stores the Kafka key, currently the order id.
- `outbox_event.payload` stores the canonical event envelope as queryable `jsonb`.
- outbox indexes on `(aggregate_type, aggregate_id)`, `(event_type, created_at)`, and `created_at`.
- `processed_events.event_id` primary key prevents the same checkout event from being applied twice.
- `processed_events(event_type, processed_at)` supports operational review of processed integration events.
- `processed_events(status, locked_until)` supports cleanup and expired `IN_PROGRESS` lease recovery.
- `idempotent_commands.id` primary key.
- `idempotent_commands(action, idempotency_key)` unique index allows only one reservation or completed response for the same action/key.
- `idempotent_commands(order_id, action, created_at desc)` supports operational lookup by order/action.
- `idempotent_commands(status, locked_until)` supports cleanup and expired `IN_PROGRESS` lease recovery.
- `order_proposals.order_id` foreign key to `orders.id` with cascade delete.
- `order_proposals.order_id` unique constraint prevents more than one active proposal per order.
- `order_proposals.items_json` stores the proposed item list as `jsonb`.

Delivery address is stored as `jsonb` because it is a checkout-time snapshot owned by the order. Store coordinates are stored as explicit numeric columns for easier querying and mapping. The combined V1 migration backfills legacy `store_lat` and `store_lng` values to `0` before the NOT NULL constraint is applied.

## Data Rule

Redis supports fast active-order reads and live state. PostgreSQL should remain the durable source for order history, terminal receipts, and audit/status history.

New checkout orders now populate the hot-view keys after the database transaction commits, with any replay rebuilding the cache from the current database order state rather than the original checkout payload:

- `order:{orderId}` snapshot
- `order:status:{orderId}`
- `order:timeline:{orderId}`
- `user:active_orders:{userId}` for ASAP orders
- `store:active_orders:{storeId}` for ASAP orders
- `store:scheduled_orders:{storeId}` for scheduled orders

Checkout event consumption first inserts the event id into `processed_events`
as `IN_PROGRESS` with a lease. If the event is already `COMPLETED`, the
handler returns without doing work. If another live instance owns an
`IN_PROGRESS` row, Spring Kafka retries with the dedicated in-progress retry
backoff. If the lease has expired, another instance can reclaim the row and
process the event. The owning instance marks the row `COMPLETED` only after
order creation and Redis hot-view initialization succeed.

Store read paths fall back to PostgreSQL if store membership keys point to
missing or unusable per-order cache data.

Customer active-order reads skip C1 entries whose C2 snapshot is missing or
belongs to a different user. Single-order customer reads still hide wrong-owner
access with `404 ORDER_NOT_FOUND`.

Driver assignment writes, driver decline, and store cancel first reserve the
`Idempotency-Key` as `IN_PROGRESS` in PostgreSQL. The owning request then writes
the order change, outbox event, and completed idempotent command response. A
concurrent retry with the same key returns `IDEMPOTENCY_REQUEST_IN_PROGRESS`;
after completion it replays the stored response. After the business commit,
assignment/decline try to evict C2, while store
cancel tries to evict `CANCELLED_ORDER_HOT_VIEWS` (C1/C1b membership removal
plus C2/C3/C4/C6 deletes). If Redis is unavailable or the eviction result is
unknown because the Redis command times out, they write an
`OrderCacheEvictionRequested` outbox event in a new transaction and return
success. Non-Redis runtime failures are allowed to propagate. A
`503 CACHE_INVALIDATION_FAILED` is returned only if direct eviction fails with a
recoverable Redis failure and the fallback eviction event cannot be recorded. A
retry with the same `Idempotency-Key` replays the stored response and repeats
the eviction path, without duplicating the order update or business outbox
event.

Store propose-changes first reserves the `Idempotency-Key` as `IN_PROGRESS`.
The owning request then writes the order status change, status history, proposal
record, `OrderProposedToCustomer` outbox event, and completed idempotent
response. After that commit, the endpoint atomically updates C8, C4, and C6
using `proposeChangesRedisUpdateScript`. If the Lua script throws a recoverable
Redis failure, `requestEvictionAfterUpdateFailure` is called, which skips any
direct eviction attempt and immediately writes one `OrderCacheEvictionRequested`
event with the `PROPOSE_CHANGES_HOT_VIEWS` strategy, so the consumer can evict
C8, C4, and C6 once Redis recovers.

Idempotent command leases default to two minutes
(`order-service.idempotency.in-progress-lease=PT2M`). Completed rows are
retained for seven days by default, abandoned `IN_PROGRESS` rows are retained
for one hour after their lock expires, and cleanup runs every ten minutes.
Cleanup defaults are configured through environment-backed application
properties: `ORDER_SERVICE_IDEMPOTENCY_COMPLETED_RETENTION`,
`ORDER_SERVICE_IDEMPOTENCY_ABANDONED_IN_PROGRESS_RETENTION`, and
`ORDER_SERVICE_IDEMPOTENCY_CLEANUP_INTERVAL_MS`.

Processed checkout event leases default to 30 seconds
(`ORDER_SERVICE_KAFKA_CHECKOUT_EVENTS_PROCESSED_EVENTS_IN_PROGRESS_LEASE=PT30S`).
Completed processed-event rows are retained for seven days by default,
abandoned `IN_PROGRESS` rows are retained for one hour after their lock expires,
and cleanup runs every ten minutes. These defaults are configured through
`ORDER_SERVICE_KAFKA_CHECKOUT_EVENTS_PROCESSED_EVENTS_COMPLETED_RETENTION`,
`ORDER_SERVICE_KAFKA_CHECKOUT_EVENTS_PROCESSED_EVENTS_ABANDONED_IN_PROGRESS_RETENTION`,
and `ORDER_SERVICE_KAFKA_CHECKOUT_EVENTS_PROCESSED_EVENTS_CLEANUP_INTERVAL_MS`.
The dedicated retry window for `ProcessedEventInProgressException` must be at
least as long as the processed-event lease. With defaults,
`ORDER_SERVICE_KAFKA_CHECKOUT_EVENTS_IN_PROGRESS_RETRY_INTERVAL_MS=5000` and
`ORDER_SERVICE_KAFKA_CHECKOUT_EVENTS_IN_PROGRESS_RETRY_MAX_ATTEMPTS=12` give a
60 second retry window for the 30 second lease; invalid shorter combinations
fail application startup.

JPA Open Session in View is disabled. Service methods should load and map all
data needed by REST responses inside explicit transactional boundaries.
