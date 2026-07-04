# Domain Model

## Order

An order represents a checked-out store basket moving through store preparation and delivery.

Planned durable order fields include:

- order id
- cart id
- user id
- store id
- store name
- items
- pricing
- delivery address
- schedule type
- scheduled time
- current status
- cancellation/refund fields
- verification code fields
- proposal timestamps

## Order Status

Current enum values:

- `PENDING`
- `CONFIRMED`
- `READY_FOR_PICKUP`
- `DRIVER_ASSIGNED`
- `OUT_FOR_DELIVERY`
- `ARRIVED`
- `DELIVERED`
- `CANCELLED`
- `SCHEDULED`
- `AWAITING_CUSTOMER_RESPONSE`

## Active Order

Active orders are non-terminal orders shown to customer and store clients.

Examples:

- `PENDING`
- `CONFIRMED`
- `READY_FOR_PICKUP`
- `DRIVER_ASSIGNED`
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

The current code contains the Redis DTO and repository foundation; event consumer and driver verification endpoint are pending.

## Timeline

Customer timeline is a simplified view of internal status:

| Internal status | Customer step |
|---|---|
| `PENDING` | `PLACED` |
| `CONFIRMED`, `READY_FOR_PICKUP` | `PACKED` |
| `OUT_FOR_DELIVERY` | `ON_THE_WAY` |
| `DELIVERED` | `ARRIVED` |

`DRIVER_ASSIGNED` is internal and has no customer-facing timeline step.
