# Domain Model

## Order

An order represents a checked-out store basket moving through store preparation and delivery.

Current durable order fields include:

- order id
- cart id
- customer id
- store id
- order items
- item-level and order-level pricing discounts
- service fee and small-order fee amounts
- fee quote id
- selected promo codes
- delivery address JSON snapshot
- delivery and store coordinates
- schedule type
- scheduled time
- current status
- driver id and assignment timestamp (nullable; set by dispatch service independently of order status)
- cancellation/refund fields

Current durable tables:

- `orders`
- `order_items`
- `order_status_history`
- `order_proposals`

`orders.cart_id` is unique so duplicate checkout events for the same cart cannot create duplicate orders.

Not yet durable:

- refund execution details (`RefundStatus` is reserved for this but not wired to any durable field yet)

## Order Item

Order items are stored as durable snapshots under `order_items`.

Each item stores:

- product id, product name, and SKU
- quantity and product unit
- unit price
- gross amount
- item discount amount
- net amount
- image URL
- line number

Quantities are decimal values. PostgreSQL stores `order_items.quantity` as
`numeric(12, 3)`, and cache/API item DTOs preserve that decimal quantity so
weighted and measured units such as `KG`, `GRAM`, `LITER`, and `ML` do not lose
precision.

`line_number` preserves the cart/receipt item order and is unique per order.
`product_id` is also unique per order. Checkout is expected to merge duplicate
products before order creation, and the database enforces that invariant so
store `OUT_OF_STOCK` rejection details can identify items by product id.

## Status History

`orders.status` stores the latest status. `order_status_history` stores each meaningful status transition for audit, timeline, debugging, and duration metrics.

Example:

```text
null -> PENDING
PENDING -> CONFIRMED
CONFIRMED -> READY_FOR_PICKUP
```

## Order Status

Current enum values:

- `PENDING`
- `CONFIRMED`
- `READY_FOR_PICKUP`
- `OUT_FOR_DELIVERY`
- `ARRIVED`
- `DELIVERED`
- `CANCELLED`
- `SCHEDULED`
- `AWAITING_CUSTOMER_RESPONSE`

Driver assignment is not a status — see `driverId` / `driverAssignedAt` fields on the Order entity.

## Cancellation Enums

`OrderCancelledBy` records who initiated the cancellation:

- `USER` — customer cancelled through the app
- `STORE` — store rejected or cancelled the order
- `SYSTEM` — platform cancelled automatically (timeout, payment failure, system error)

`OrderCancellationReason` records why it was cancelled:

- `USER_REQUESTED`, `STORE_REJECTED`, `OUT_OF_STOCK`, `STORE_CLOSED`, `STORE_UNABLE_TO_FULFIL`
- `NO_RIDERS_AVAILABLE`, `PAYMENT_FAILED`, `STORE_RESPONSE_TIMEOUT`
- `AWAITING_CUSTOMER_RESPONSE_TIMEOUT`, `SYSTEM_ERROR`

## Refund Status

`RefundStatus` tracks the post-cancellation refund state:

- `REFUND_PENDING` — refund initiated but not yet settled
- `REFUNDED` — refund successfully completed

Not yet wired to any durable field; reserved for the refund workflow.

## Active Order

Active orders are non-terminal orders shown to customer and store clients.

Examples:

- `PENDING`
- `CONFIRMED`
- `READY_FOR_PICKUP`
- `OUT_FOR_DELIVERY`
- `ARRIVED`
- `AWAITING_CUSTOMER_RESPONSE`

## Scheduled Order

Scheduled orders are future orders. The activation job moves them from scheduled storage into the active queue before the requested delivery window, based on the configured activation lead time.

## Proposal

A proposal is created when a store discovers stock issues after accepting an order. The full flow is implemented:

1. Store submits item quantity changes (`POST /api/v1/store/orders/{orderId}/propose-changes`). Order transitions `CONFIRMED -> AWAITING_CUSTOMER_RESPONSE`; a durable `order_proposals` row is created with `status = ACTIVE`; the proposal is cached under `order:proposals:{orderId}` (C8).
2. Customer responds (`POST /api/v1/orders/{orderId}/respond-to-changes`):
   - accept (all or with modifications) marks the proposal `ACCEPTED` and emits `OrderProposalAccepted` for promo-service to recalculate pricing; the order stays `AWAITING_CUSTOMER_RESPONSE` until the promo-service callback applies the recalculated totals
   - cancel marks the proposal `REJECTED` and transitions the order to `CANCELLED`
3. The promo-service callback (`POST /api/v1/internal/orders/{orderId}/update-after-proposal`) applies the recalculated items/totals, marks the proposal `APPLIED`, and returns the order to `CONFIRMED`; C8 is deleted.
4. A store/admin can instead withdraw the proposal without cancelling the order (`cancel-active-proposal`, store or internal), which marks it `CANCELLED` and returns the order to `CONFIRMED` so a corrected proposal can be submitted later.
5. If the customer never responds, `ProposalTimeoutScheduler` automatically cancels the order (`cancel-proposal-and-order` transition, reason `AWAITING_CUSTOMER_RESPONSE_TIMEOUT`) once the configured response window elapses; the proposal is marked `TIMED_OUT`. The same transition is also available as a manual internal endpoint.

`ProposalStatus` tracks the full lifecycle: `ACTIVE`, `ACCEPTED`, `APPLIED`, `REJECTED`, `CANCELLED`, `TIMED_OUT`, `SYSTEM_CANCELLED`, `STORE_CANCELLED`. At most one `ACTIVE` proposal per order is enforced by a partial unique index.

## Verification Code

The delivery verification code flow is implemented. A 2-digit code is generated when the driver marks the order `ARRIVED` (`POST /api/v1/driver/orders/{orderId}/arrive`).

Cache key:

```text
order:verification:{orderId}
```

The code is written to C7 with a 30-minute TTL and also persisted to `order_status_history.metadata_json` as a permanent fallback once C7 expires. The driver submits it via `POST /api/v1/driver/orders/{orderId}/complete`, which validates against C7 first, then the DB fallback. Only the Kafka `order.arrived` delivery-arrival event consumer (a separate concern from verification) is pending.

## Timeline

Customer timeline is a simplified view of internal status:

| Internal status | Customer step |
|---|---|
| `PENDING` | `PLACED` |
| `READY_FOR_PICKUP` | `PACKED` |
| `OUT_FOR_DELIVERY` | `ON_THE_WAY` |
| `ARRIVED` | `ARRIVED` |
| `DELIVERED` | `DELIVERED` |

`CONFIRMED` means the store accepted the order and is preparing it. It does not
advance the customer timeline to `PACKED`.
