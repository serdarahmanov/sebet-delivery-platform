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

`orders.cart_id` is unique so duplicate checkout events for the same cart cannot create duplicate orders.

Not yet durable:

- refund execution details
- verification code state
- proposal timestamps
- delivery assignment details

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

Scheduled orders are future orders. Planned behavior moves them from scheduled storage into the active queue 30 minutes before the requested delivery window.

## Proposal

A proposal is created when a store discovers stock issues after accepting an order.

Planned proposal flow:

1. Store submits item quantity changes.
2. Order transitions to `AWAITING_CUSTOMER_RESPONSE`.
3. Proposal is cached under `order:proposals:{orderId}`.
4. Customer accepts, modifies, or cancels.
5. Proposal cache is deleted.
6. Order returns to `CONFIRMED` or transitions to `CANCELLED`.

## Verification Code

The planned delivery verification code is generated when delivery reaches the customer.

Cache key:

```text
order:verification:{orderId}
```

The current code contains the Redis DTO and repository foundation. The delivery-arrival event consumer and driver verification endpoint are pending.

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
