# Data Storage

Order-service is designed to use PostgreSQL for durable order history and Redis for hot order views.

## Current State

Implemented:

- Redis key registry.
- Redis DTOs.
- Redis repository classes.
- Redis Lua script beans for lock release and active-set cleanup.

Pending:

- JPA entities.
- JPA repositories.
- Flyway migrations.
- durable order status history table.

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
| C4 | `order:status:{orderId}` | STRING | Current order status |
| C5 | `order:lock:{cartId}` | STRING | Order creation lock |
| C6 | `order:timeline:{orderId}` | LIST/JSON | Customer timeline |
| C7 | `order:verification:{orderId}` | STRING/JSON | Delivery verification code |
| C8 | `order:proposals:{orderId}` | STRING/JSON | Active item-change proposal |

## Redis Rules

- C2 static order snapshot has a 48-hour TTL.
- C3 tracking data is intended to be short-lived live state.
- C4 status is separated from C2 to allow cheap status reads.
- C5 lock uses `SET NX EX` with a 30-second TTL.
- C6 timeline is append-only customer-facing progress.
- C7 verification code is short-lived and generated near delivery arrival.
- C8 proposal data exists only while waiting for customer response.

## Lua Scripts

`OrderRedisConfig` registers:

- `releaseLockScript`: deletes `order:lock:{cartId}` only when the stored owner matches the caller.
- `removeActiveOrderScript`: removes an order id from an active SET and deletes the key if the set becomes empty.

These scripts close common Redis race windows.

## Planned PostgreSQL Schema

The durable schema should store:

- order header
- order items
- delivery address
- pricing
- cancellation fields
- refund fields
- verification fields
- proposal timestamps
- order status history

No Flyway migration currently exists in this service.

## Data Rule

Redis supports fast active-order reads and live state. PostgreSQL should remain the durable source for order history, terminal receipts, and audit/status history.
