# Order Lifecycle

## Normal Flow

```text
PENDING
  -> CONFIRMED
  -> READY_FOR_PICKUP
  -> OUT_FOR_DELIVERY
  -> ARRIVED
  -> DELIVERED
```

## Implemented Store Transitions

The first store lifecycle slice is implemented:

| From status | Action | Result | Endpoint |
|---|---|---|---|
| `PENDING` | accept | `CONFIRMED` | `POST /api/v1/store/orders/{orderId}/accept` |
| `PENDING` | reject | `CANCELLED` | `POST /api/v1/store/orders/{orderId}/reject` |
| `CONFIRMED` | ready | `READY_FOR_PICKUP` | `POST /api/v1/store/orders/{orderId}/ready` |
| `CONFIRMED` | cancel | `CANCELLED` | `POST /api/v1/store/orders/{orderId}/cancel` |
| `AWAITING_CUSTOMER_RESPONSE` | cancel | `CANCELLED` | `POST /api/v1/store/orders/{orderId}/cancel` |
| `CONFIRMED` | propose changes | `AWAITING_CUSTOMER_RESPONSE` | `POST /api/v1/store/orders/{orderId}/propose-changes` |

Implemented transitions:

- validate the current status before changing it
- use the `orders.version` optimistic lock to prevent concurrent lifecycle writes from both committing
- verify the order belongs to the calling store, returning 404 when it does not
- update the `orders` row
- append an `order_status_history` row
- update Redis after the database transaction commits
- return `409 ORDER_INVALID_TRANSITION` for invalid state changes or stale concurrent updates

Redis behavior:

- `accept` updates C4 (`order:status:{orderId}`)
- `ready` updates C4 and appends `PACKED` to C6 (`order:timeline:{orderId}`) if it is missing
- `reject` moves the order to `CANCELLED`, removes it from active user/store sets, and deletes hot order/status/tracking/timeline keys
- post-acceptance `cancel` moves the order to `CANCELLED`, then uses `CANCELLED_ORDER_HOT_VIEWS` to atomically remove C1/C1b memberships and delete C2, C3, C4, and C6 through the fallback eviction pattern
- `propose-changes` atomically writes the proposal JSON to C8 (`order:proposals:{orderId}`), updates C4 status value, and appends an `AWAITING_CUSTOMER_RESPONSE` timeline entry to C6 using `proposeChangesRedisUpdateScript`; if the Lua script fails with a recoverable Redis error, the endpoint falls back to `requestEvictionAfterUpdateFailure` with the `PROPOSE_CHANGES_HOT_VIEWS` strategy

## Implemented Driver Transitions

The driver delivery slice is implemented:

| From status | Action | Result | Endpoint |
|---|---|---|---|
| `READY_FOR_PICKUP` | pickup | `OUT_FOR_DELIVERY` | `POST /api/v1/driver/orders/{orderId}/pickup` |
| `OUT_FOR_DELIVERY` | arrive | `ARRIVED` | `POST /api/v1/driver/orders/{orderId}/arrive` |
| `ARRIVED` | complete | `DELIVERED` | `POST /api/v1/driver/orders/{orderId}/complete` |

Driver transitions:

- verify the order's `driverId` matches `X-Driver-Id`, returning `403 DRIVER_NOT_ASSIGNED` when it does not
- validate the current status before changing it
- update the `orders` row; `complete` sets `delivered_at` to the same instant as `changed_at` for timestamp consistency
- append an `order_status_history` row with `changed_by_type = DRIVER`; `arrive` persists the verification code in `metadata_json` as a permanent fallback
- update Redis after the database transaction commits

Redis behavior:

- `pickup` updates C4 and appends `ON_THE_WAY` to C6 if missing
- `arrive` updates C4, appends `ARRIVED` to C6 if missing, and writes the verification code to C7 (30-minute TTL)
- `complete` updates C4, appends `DELIVERED` to C6, removes the order from C1 and C1b active sets, and deletes C2, C3, and C7

## Verification Code Flow

1. Driver hits `/arrive` → a 2-digit zero-padded code (`"00"`–`"99"`) is generated and written to C7 with a 30-minute TTL. The same code is also stored in `order_status_history.metadata_json` as a permanent fallback.
2. Customer reads the code from `GET /api/v1/orders/{orderId}/verification-code` (C7) and shows it to the driver.
3. Driver submits the code via `POST /api/v1/driver/orders/{orderId}/complete`. The service reads C7 first; if C7 has expired, it falls back to the `order_status_history` record for the `ARRIVED` transition.
4. Code mismatch → `400 VALIDATION_ERROR`. Code not found in either store → `404 VERIFICATION_CODE_NOT_FOUND`.

`OUT_OF_STOCK` reject validation:

- `outOfStockItems` is required and must be non-empty
- each listed product must belong to the order
- requested quantity and unit must match the persisted order item
- duplicate product IDs are rejected
- duplicate products in persisted order items are prevented by the database
- `availableQuantity == null` means no stock is available
- non-null `availableQuantity` must be greater than zero and less than the requested quantity
- validated rejection details are stored in `order_status_history.metadata_json`

## Driver Assignment

Driver assignment is not a status step. It is stored as `driverId` and `driverAssignedAt` metadata fields on the order.

A courier can be dispatched before or after the store marks `READY_FOR_PICKUP`. The two tracks are independent. The driver's pickup call (`POST /api/v1/driver/orders/{orderId}/pickup`) transitions `READY_FOR_PICKUP -> OUT_FOR_DELIVERY` and requires both conditions to be satisfied:

1. Order status is `READY_FOR_PICKUP`
2. `driverId` is set on the order

If the driver arrives at the store before the order is ready, they wait. The status remains `READY_FOR_PICKUP` until both conditions are true.

## Scheduled Flow

```text
SCHEDULED
  -> PENDING
```

Scheduled orders enter the active queue before their requested delivery time.
The automatic transition job uses the configured activation lead time to find
due `SCHEDULED` orders and move them to `PENDING`.

The implemented internal `POST /api/v1/internal/orders/{orderId}/activate-scheduled`
endpoint remains available as a manual admin/support override for `SCHEDULED -> PENDING`.

## Proposal Flow

```text
CONFIRMED
  -> AWAITING_CUSTOMER_RESPONSE
  -> CONFIRMED
```

If the customer rejects the proposal or does not respond in time:

```text
AWAITING_CUSTOMER_RESPONSE
  -> CANCELLED
```

Proposal cancellation has two distinct endpoint families:

- store/internal `cancel-active-proposal` removes the active proposal without cancelling the order, moves the order back to `CONFIRMED`, deletes C8, updates C4, and removes `AWAITING_CUSTOMER_RESPONSE` entries from C6 so a corrected proposal can be submitted later.
- `cancel-proposal-and-order` removes the active proposal and cancels the order.

The `CONFIRMED -> AWAITING_CUSTOMER_RESPONSE` transition is implemented. Customer response, proposal cancellation, proposal timeout cancellation, and promo-service proposal application are implemented.

## Cancellation Paths

Implemented cancellation paths:

- store rejects while `PENDING`
- store cancels after acceptance while `CONFIRMED` or `AWAITING_CUSTOMER_RESPONSE`

Planned cancellation paths:

- customer cancels before cutoff
- system cancels after store/customer timeout
- system cancels on unrecoverable processing failure

## Terminal States

- `DELIVERED`
- `CANCELLED`

## Customer Timeline

The customer sees five steps on the normal delivery path:

1. `PLACED`
2. `PACKED`
3. `ON_THE_WAY`
4. `ARRIVED`
5. `DELIVERED`

This timeline is intentionally simpler than the internal order status enum. `CONFIRMED` does not map to a customer step. Each step maps to a status transition:

| Timeline step | Appended on transition to |
|---|---|
| `PLACED` | Written at order creation |
| `PACKED` | `READY_FOR_PICKUP` |
| `ON_THE_WAY` | `OUT_FOR_DELIVERY` |
| `ARRIVED` | `ARRIVED` |
| `DELIVERED` | `DELIVERED` |

`PACKED`, `ON_THE_WAY`, and `ARRIVED` are deduplicated before append. `DELIVERED` is appended unconditionally since it is terminal and fires exactly once. On cancellation the entire C6 timeline is deleted.

When the store proposes changes, an additional `AWAITING_CUSTOMER_RESPONSE` entry is appended to C6 (alongside the C8 and C4 updates). This entry signals to the customer app that a decision is pending. It is not one of the five delivery-path steps and does not affect `PACKED`, `ON_THE_WAY`, `ARRIVED`, or `DELIVERED` deduplication logic.

When the proposal flow is resolved without cancelling the order, C6 does not get a new delivery-path step. Both internal `cancel-active-proposal` and promo-service `update-after-proposal` remove `AWAITING_CUSTOMER_RESPONSE` entries from C6 and set C4 back to `CONFIRMED`. The promo callback also deletes C2 because totals/items changed and the next read must rebuild the order snapshot from PostgreSQL.
