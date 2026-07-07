# Data Storage

Order-service is designed to use PostgreSQL for durable order history and Redis for hot order views.

## Current State

Implemented:

- Redis key registry.
- Redis DTOs.
- Redis repository classes.
- Redis Lua script beans for lock release and active-set cleanup.
- JPA entities for orders, order items, and order status history.
- Spring Data JPA repositories.
- Flyway migration `V1__create_order_tables.sql`.
- JSONB mapping for the delivery address snapshot and status metadata.
- Unique `orders.cart_id` idempotency constraint.
- Unique per-order `order_items.line_number` constraint.
- Unique per-order `order_items.product_id` constraint.
- Optimistic locking on `orders.version`.

Pending:

- Durable proposal/refund/verification extensions if those states need long-term storage.

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

These scripts close common Redis race windows.

## PostgreSQL Schema

The current durable schema stores:

- `orders`: durable order header, optimistic lock version, cart id, customer/store ids, status, schedule fields, totals, discounts, delivery address snapshot, coordinates, cancellation fields, driver assignment fields (`driver_id`, `driver_assigned_at`), and timestamps.
- `order_items`: durable item snapshots with product, quantity, unit, pricing, discount, image URL, and line number.
- `order_status_history`: append-style status transition records.

Important constraints and indexes:

- `orders.id` primary key.
- `orders.cart_id` unique index for checkout idempotency.
- `orders.version` optimistic lock column used by JPA lifecycle writes.
- `order_items.order_id` foreign key to `orders.id` with cascade delete.
- `order_items(order_id, line_number)` unique index.
- `order_items(order_id, product_id)` unique index.
- `order_status_history.order_id` foreign key to `orders.id` with cascade delete.
- customer/store history indexes on `(customer_id, created_at desc)` and `(store_id, created_at desc)`.

Delivery address is stored as `jsonb` because it is a checkout-time snapshot owned by the order. Coordinates are stored as explicit numeric columns for easier querying and mapping.

## Data Rule

Redis supports fast active-order reads and live state. PostgreSQL should remain the durable source for order history, terminal receipts, and audit/status history.

New checkout orders now populate the hot-view keys after the database transaction commits, with any replay rebuilding the cache from the current database order state rather than the original checkout payload:

- `order:{orderId}` snapshot
- `order:status:{orderId}`
- `order:timeline:{orderId}`
- `user:active_orders:{userId}` for immediate orders
- `store:active_orders:{storeId}` for immediate orders
- `store:scheduled_orders:{storeId}` for scheduled orders

Store read paths fall back to PostgreSQL if store membership keys point to
missing or unusable per-order cache data.

JPA Open Session in View is disabled. Service methods should load and map all
data needed by REST responses inside explicit transactional boundaries.
