# API Design

## Customer API

Base path:

```text
/api/v1/orders
```

Required header:

```http
X-User-Id: <user-id>
```

Endpoint groups:

- `GET /api/v1/orders`
- `GET /api/v1/orders/active`
- `GET /api/v1/orders/active/{orderId}`
- `GET /api/v1/orders/scheduled/{orderId}`
- `PATCH /api/v1/orders/scheduled/{orderId}`
- `POST /api/v1/orders/scheduled/{orderId}/activate-now`
- `GET /api/v1/orders/cancelled/{orderId}`
- `GET /api/v1/orders/{orderId}`
- `GET /api/v1/orders/{orderId}/status`
- `GET /api/v1/orders/{orderId}/tracking`
- `GET /api/v1/orders/{orderId}/verification-code`
- `GET /api/v1/orders/{orderId}/proposed-changes`
- `POST /api/v1/orders/{orderId}/respond-to-changes`
- `POST /api/v1/orders/{orderId}/cancel`

## Store API

Base path:

```text
/api/v1/store/orders
```

Required header:

```http
X-Store-Id: <store-id>
```

Additional required header for `POST /api/v1/store/orders/{orderId}/cancel`, `POST /api/v1/store/orders/{orderId}/propose-changes`, and `POST /api/v1/store/orders/{orderId}/cancel-active-proposal`:

```http
Idempotency-Key: <unique-command-key>
```

Endpoint groups:

- `GET /api/v1/store/orders`
- `GET /api/v1/store/orders/active`
- `GET /api/v1/store/orders/scheduled`
- `GET /api/v1/store/orders/{orderId}`
- `GET /api/v1/store/orders/{orderId}/status`
- `POST /api/v1/store/orders/{orderId}/accept`
- `POST /api/v1/store/orders/{orderId}/reject`
- `POST /api/v1/store/orders/{orderId}/ready`
- `POST /api/v1/store/orders/{orderId}/cancel`
- `POST /api/v1/store/orders/{orderId}/propose-changes`
- `POST /api/v1/store/orders/{orderId}/cancel-active-proposal`

## Driver API

Base path:

```text
/api/v1/driver/orders
```

Required header:

```http
X-Driver-Id: <driver-id>
```

Additional required header for assignment decline:

```http
Idempotency-Key: <unique-command-key>
```

Endpoint groups:

- `GET  /api/v1/driver/orders/{orderId}` - delivery detail (C2 + C4)
- `POST /api/v1/driver/orders/{orderId}/pickup` - `READY_FOR_PICKUP -> OUT_FOR_DELIVERY`
- `POST /api/v1/driver/orders/{orderId}/arrive` - `OUT_FOR_DELIVERY -> ARRIVED`; generates verification code into C7
- `POST /api/v1/driver/orders/{orderId}/complete` - `ARRIVED -> DELIVERED`; validates code from C7
- `POST /api/v1/driver/orders/{orderId}/decline` - unassigns driver; valid only before `OUT_FOR_DELIVERY`

GPS and ETA updates do not go through this API. The driver app sends coordinates
to the tracking service, which publishes a `DriverLocationUpdatedEvent`. Order-service
consumes that event, updates Cache 3 (`movementStatus`, `driverLat`, `driverLng`,
`etaMinutes`), and pushes a WebSocket message to the customer.

## Internal API

Base path:

```text
/api/v1/internal/orders
```

Required header:

```http
X-Internal-Key: <shared-secret>
```

Driver assignment and internal lifecycle writes also require:

```http
Idempotency-Key: <unique-command-key>
```

Not exposed to customers, stores, or drivers. Intended for the dispatch service,
admin/ops tooling, and other internal platform services.

Endpoint groups:

- `POST /api/v1/internal/orders/{orderId}/assign-driver` - sets `driverId` and `driverAssignedAt`
- `POST /api/v1/internal/orders/{orderId}/unassign-driver` - clears `driverId` with no replacement; valid on any non-terminal status
- `POST /api/v1/internal/orders/{orderId}/admin-cancel` - admin override cancellation
- `POST /api/v1/internal/orders/{orderId}/system-cancel` - system-initiated cancellation
- `POST /api/v1/internal/orders/{orderId}/activate-scheduled` - `SCHEDULED -> PENDING`
- `POST /api/v1/internal/orders/{orderId}/cancel-active-proposal` - cancel active proposal only
- `POST /api/v1/internal/orders/{orderId}/cancel-proposal-and-order` - `AWAITING_CUSTOMER_RESPONSE -> CANCELLED`

## Current Implementation Status

### Customer API - GET Endpoints

The following customer read endpoints are fully implemented in `CustomerOrderQueryService`:

| Endpoint | Source | Status |
|---|---|---|
| `GET /api/v1/orders` | DB | implemented |
| `GET /api/v1/orders/active` | C1 -> C2 with user ownership filter | implemented |
| `GET /api/v1/orders/active/{orderId}` | C2 + C4 + C6 + C7 | implemented |
| `GET /api/v1/orders/scheduled/{orderId}` | DB | implemented |
| `GET /api/v1/orders/cancelled/{orderId}` | DB | implemented |
| `GET /api/v1/orders/{orderId}` | DB smart router | implemented |
| `GET /api/v1/orders/{orderId}/status` | C4 -> DB | implemented |
| `GET /api/v1/orders/{orderId}/tracking` | C4 + C3 | implemented |
| `GET /api/v1/orders/{orderId}/verification-code` | C7 | implemented |
| `GET /api/v1/orders/{orderId}/proposed-changes` | C8 | implemented |

Customer single-order read ownership checks return `404 ORDER_NOT_FOUND` for
both missing orders and wrong-user orders. The customer active-order list also
filters C2 snapshots by `userId`, so stale or corrupt C1 membership cannot
return another customer's order.

### Customer API - Write Endpoints

The following customer write endpoints are implemented in `CustomerOrderLifecycleService`:

| Endpoint | Behavior | Status |
|---|---|---|
| `PATCH /api/v1/orders/scheduled/{orderId}` | partial update — delivery time, address, and/or phone number; validates slot alignment, min lead time, store working hours, and modification window | implemented |
| `POST /api/v1/orders/scheduled/{orderId}/activate-now` | `SCHEDULED -> PENDING`; customer-initiated immediate dispatch | implemented |
| `POST /api/v1/orders/{orderId}/respond-to-changes` | accept (all or with modifications) or cancel in response to a store proposal | implemented |
| `POST /api/v1/orders/{orderId}/cancel` | `PENDING`, `CONFIRMED`, or `SCHEDULED` -> `CANCELLED`; reason fixed to `USER_REQUESTED` | implemented |

### Store API - Read Endpoints

The following store read endpoints are implemented in `StoreOrderQueryService`:

| Endpoint | Source | Status |
|---|---|---|
| `GET /api/v1/store/orders` | DB | implemented |
| `GET /api/v1/store/orders/active` | C1b -> C2 + C4, DB fallback | implemented |
| `GET /api/v1/store/orders/scheduled` | C1c -> C2, DB fallback | implemented |
| `GET /api/v1/store/orders/{orderId}` | DB + status history, C8 proposal merge | implemented |
| `GET /api/v1/store/orders/{orderId}/status` | C4 ownership/status read, DB fallback | implemented |

Store read ownership checks return `404 ORDER_NOT_FOUND` for both missing orders and wrong-store orders.

The following store write endpoints are implemented through `StoreOrderLifecycleService` and `OrderLifecycleService`:

| Endpoint | Transition | Status |
|---|---|---|
| `POST /api/v1/store/orders/{orderId}/accept` | `PENDING -> CONFIRMED` | implemented |
| `POST /api/v1/store/orders/{orderId}/reject` | `PENDING -> CANCELLED` | implemented |
| `POST /api/v1/store/orders/{orderId}/ready` | `CONFIRMED -> READY_FOR_PICKUP` | implemented |
| `POST /api/v1/store/orders/{orderId}/cancel` | `CONFIRMED` or `AWAITING_CUSTOMER_RESPONSE` -> `CANCELLED` | implemented |
| `POST /api/v1/store/orders/{orderId}/propose-changes` | `CONFIRMED` -> `AWAITING_CUSTOMER_RESPONSE` | implemented |
| `POST /api/v1/store/orders/{orderId}/cancel-active-proposal` | cancel active proposal only, allowing a corrected proposal later | pending (controller stub; internal equivalent works via `POST /api/v1/internal/orders/{orderId}/cancel-active-proposal`) |

Invalid lifecycle transitions return `409 Conflict` with `ORDER_INVALID_TRANSITION`.
Orders that do not exist or do not belong to the calling store return `404 ORDER_NOT_FOUND`.

Store `/cancel` requires `Idempotency-Key`. The cancelled status, status history, lifecycle outbox event, and idempotent command response commit in the same database transaction. Reusing the same key with the same request returns the stored response; reusing it with a different request returns `409 IDEMPOTENCY_KEY_CONFLICT`. After commit and on idempotent replay, the endpoint runs `CANCELLED_ORDER_HOT_VIEWS` eviction through the cache-eviction fallback pattern. That strategy uses one Redis Lua script to `SREM` the order from C1/C1b and `DEL` C2, C3, C4, and C6 atomically. Recoverable Redis connection failures or command timeouts write one `OrderCacheEvictionRequested` event containing all target `cacheKeys`; non-Redis runtime failures propagate.

Store `/propose-changes` requires `Idempotency-Key`. The endpoint validates that the product names, units, and requested quantities in the proposal match the stored order items. The updated status, status history, proposal record, `OrderProposedToCustomer` outbox event, and idempotent command response commit in the same database transaction. Reusing the same key with the same request returns the stored response; reusing it with a different request returns `409 IDEMPOTENCY_KEY_CONFLICT`. After the database transaction commits, the endpoint atomically updates C8 (proposal JSON), C4 (status value), and C6 (timeline entry) using a single Redis Lua script. If the Lua script throws a recoverable Redis availability failure, `requestEvictionAfterUpdateFailure` is called with the `PROPOSE_CHANGES_HOT_VIEWS` strategy, which skips the direct eviction attempt and writes one `OrderCacheEvictionRequested` event immediately. Non-Redis runtime failures propagate.

### Driver API - Endpoints

The following driver endpoints are implemented through `DriverOrderLifecycleService` and `OrderLifecycleService` where status transitions are required:

| Endpoint | Transition | Status |
|---|---|---|
| `GET /api/v1/driver/orders/{orderId}` | read assigned delivery detail from C2 + C4, DB fallback | implemented |
| `POST /api/v1/driver/orders/{orderId}/pickup` | `READY_FOR_PICKUP -> OUT_FOR_DELIVERY` | implemented |
| `POST /api/v1/driver/orders/{orderId}/arrive` | `OUT_FOR_DELIVERY -> ARRIVED` | implemented |
| `POST /api/v1/driver/orders/{orderId}/complete` | `ARRIVED -> DELIVERED` | implemented |
| `POST /api/v1/driver/orders/{orderId}/decline` | clears `driverId`, status unchanged | implemented |

Driver ownership is verified on every driver endpoint: the `driverId` on the order must match `X-Driver-Id`. A mismatch returns `403 DRIVER_NOT_ASSIGNED`. Orders that do not exist return `404 ORDER_NOT_FOUND`. Invalid transitions return `409 ORDER_INVALID_TRANSITION`.

`/decline` is idempotent by `Idempotency-Key`. The order update, `DriverAssignmentDeclined` outbox event, and idempotency record commit in the same database transaction. After commit, order-service tries to evict C2 (`order:{orderId}`) so the next Redis-first read rebuilds from PostgreSQL. If Redis is unavailable or the Redis command result is unknown, order-service writes a deliberate `OrderCacheEvictionRequested` outbox event and still returns `200`. Non-Redis runtime failures propagate normally. If that fallback event cannot be recorded, the endpoint returns `503 CACHE_INVALIDATION_FAILED`; callers should retry the same request with the same idempotency key.

`/arrive` generates a 2-digit zero-padded verification code (`"00"`–`"99"`), writes it to C7 (30-minute TTL), and persists it to `order_status_history.metadata_json` as a permanent fallback.

`/complete` requires the driver to submit the code shown to the customer. The code is validated against C7, falling back to `order_status_history` if C7 has expired. A wrong code returns `400 VALIDATION_ERROR`. A missing code (not in Redis and not in DB) returns `404 VERIFICATION_CODE_NOT_FOUND`.

### Internal API

The following internal endpoints are implemented through `InternalDriverAssignmentService` and `OrderLifecycleService`:

| Endpoint | Behavior | Status |
|---|---|---|
| `POST /api/v1/internal/orders/{orderId}/assign-driver` | assigns a new driver, idempotently returns the existing assignment for the same driver, or replaces a different driver | implemented |
| `POST /api/v1/internal/orders/{orderId}/unassign-driver` | clears the current driver on any non-terminal order | implemented |
| `POST /api/v1/internal/orders/{orderId}/admin-cancel` | admin override cancellation for any order that is not `DELIVERED` or `CANCELLED` | implemented |
| `POST /api/v1/internal/orders/{orderId}/system-cancel` | automated system cancellation for pre-delivery orders | implemented |
| `POST /api/v1/internal/orders/{orderId}/activate-scheduled` | `SCHEDULED -> PENDING` | implemented |
| `POST /api/v1/internal/orders/{orderId}/cancel-active-proposal` | cancel active proposal only, returning order to `CONFIRMED` | implemented |
| `POST /api/v1/internal/orders/{orderId}/cancel-proposal-and-order` | `AWAITING_CUSTOMER_RESPONSE -> CANCELLED` with reason `AWAITING_CUSTOMER_RESPONSE_TIMEOUT` | implemented |

Assignment writes are idempotent by `Idempotency-Key`. The order update, assignment outbox event, and idempotency record commit in the same database transaction. After commit, order-service tries to evict C2 (`order:{orderId}`) so the next Redis-first read rebuilds from PostgreSQL. If Redis is unavailable or the Redis command result is unknown, order-service writes a deliberate `OrderCacheEvictionRequested` outbox event and still returns `200`. Non-Redis runtime failures propagate normally. If that fallback event cannot be recorded, the endpoint returns `503 CACHE_INVALIDATION_FAILED`; callers should retry the same request with the same idempotency key. Reusing the same key for a different request body returns `409 IDEMPOTENCY_KEY_CONFLICT`.

Scheduled activation is an admin/support manual activation endpoint, not the automatic scheduled-order job. It is idempotent by `Idempotency-Key`. The status transition, `OrderActivated` outbox event, and idempotent command response commit in the same PostgreSQL transaction. After commit and on idempotent replay, it atomically removes the order from Cache 1c, adds it to Cache 1 and Cache 1b, and writes Cache 4 through a Lua script. Recoverable Redis failures record an `OrderCacheEvictionRequested` event for `SCHEDULED_ACTIVATION_HOT_VIEWS`; non-Redis runtime failures still propagate. The future scheduled-order job should use a separate workflow that validates due-time semantics before activation.

Admin cancel is idempotent by `Idempotency-Key` and is reserved for admin/support tooling. It accepts the same reason set as system cancel, but it can cancel only orders that are not already `DELIVERED` or `CANCELLED`. Delivered orders should be handled by refund or settlement adjustment workflows instead of rewriting the lifecycle status.

System cancel is idempotent by `Idempotency-Key`. The endpoint is for automated internal flows and accepts only system-owned reasons: `PAYMENT_FAILED`, `NO_RIDERS_AVAILABLE`, `STORE_RESPONSE_TIMEOUT`, `AWAITING_CUSTOMER_RESPONSE_TIMEOUT`, and `SYSTEM_ERROR`. It can cancel `PENDING`, `CONFIRMED`, `AWAITING_CUSTOMER_RESPONSE`, `SCHEDULED`, or `READY_FOR_PICKUP` orders, and rejects `OUT_FOR_DELIVERY`, `ARRIVED`, `DELIVERED`, and already `CANCELLED` orders. Both cancellation paths write the cancellation status, status history, lifecycle outbox event, and idempotent command response in one PostgreSQL transaction. After commit and on idempotent replay, Redis cleanup uses `CANCELLED_ORDER_HOT_VIEWS`, which removes C1/C1b membership and deletes C2/C3/C4/C6 through the recoverable eviction fallback pattern.

Internal `cancel-active-proposal` is idempotent by `Idempotency-Key`. It requires the order to be `AWAITING_CUSTOMER_RESPONSE` and requires a durable active proposal row. The database transaction marks the proposal row as `CANCELLED` (retained as an audit record), moves the order back to `CONFIRMED`, writes status history, emits `OrderActiveProposalCancelled`, and stores the idempotent command response. After commit and on idempotent replay, a Redis Lua script deletes C8, writes C4 as `CONFIRMED`, and removes `AWAITING_CUSTOMER_RESPONSE` entries from C6 without removing the rest of the timeline. Recoverable Redis failures write an `OrderCacheEvictionRequested` event for `CANCEL_ACTIVE_PROPOSAL_HOT_VIEWS`.

Internal `cancel-proposal-and-order` is idempotent by `Idempotency-Key`. It requires the order to be `AWAITING_CUSTOMER_RESPONSE`. The database transaction marks the proposal row as `TIMED_OUT` (retained as an audit record), transitions the order to `CANCELLED` with reason `AWAITING_CUSTOMER_RESPONSE_TIMEOUT`, writes status history, emits `OrderCancelled`, and stores the idempotent command response. After commit and on idempotent replay, Redis cleanup uses `CANCELLED_ORDER_HOT_VIEWS`, which removes C1/C1b membership and deletes C2/C3/C4/C6/C8 through the recoverable eviction fallback pattern.

## Response Design

Customer and store response DTOs are intentionally separate.

Shared subtypes exist for repeated response fragments such as:

- customer info
- delivery address
- order item
- pricing
- store location

## Status Code Intent

Current and planned status code conventions:

- `200 OK`: successful reads and actions with response body.
- `302 Found`: smart customer order router redirects to status-specific detail endpoints.
- `400 Bad Request`: missing or invalid required identity header (`X-User-Id`, `X-Store-Id`, `X-Driver-Id`), or submitted verification code does not match stored code.
- `401 Unauthorized`: missing `X-Internal-Key` header on internal endpoints.
- `403 Forbidden`: invalid `X-Internal-Key` value, or `X-Driver-Id` does not match the driver assigned to the order.
- `404 Not Found`: order/proposal/code not found, or wrong order owner where ownership should be hidden.
- `409 Conflict`: invalid lifecycle transition, modification outside allowed window, or reused `Idempotency-Key` with a different request.
- `503 Service Unavailable`: write committed, direct Redis hot-view eviction failed with a recoverable Redis failure, and the fallback `OrderCacheEvictionRequested` event could not be recorded; retry the same request, reusing the same `Idempotency-Key` only for endpoints that require one.
