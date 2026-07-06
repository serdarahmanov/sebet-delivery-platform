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

## Driver Assignment

Driver assignment is NOT a status step. It is stored as `driverId` and `driverAssignedAt` metadata fields on the order.

A courier can be dispatched before or after the store marks `READY_FOR_PICKUP` — the two tracks are independent. The driver's pickup call (`POST /api/v1/driver/orders/{orderId}/pickup`) transitions `READY_FOR_PICKUP → OUT_FOR_DELIVERY` and requires both conditions to be satisfied:

1. Order status is `READY_FOR_PICKUP`
2. `driverId` is set on the order

If the driver arrives at the store before the order is ready, they wait — the status remains `READY_FOR_PICKUP` until both conditions are true.

## Scheduled Flow

```text
SCHEDULED
  -> PENDING
```

Scheduled orders are planned to enter the active queue 30 minutes before their requested delivery time.

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

## Cancellation Paths

Planned cancellation paths:

- customer cancels before cutoff
- store rejects while `PENDING`
- store cancels after acceptance
- system cancels after store/customer timeout
- system cancels on unrecoverable processing failure

## Terminal States

- `DELIVERED`
- `CANCELLED`

## Store Actions

| From status | Action | Result |
|---|---|---|
| `PENDING` | accept | `CONFIRMED` |
| `PENDING` | reject | `CANCELLED` |
| `CONFIRMED` | ready | `READY_FOR_PICKUP` |
| `CONFIRMED` | propose changes | `AWAITING_CUSTOMER_RESPONSE` |
| `CONFIRMED` | cancel | `CANCELLED` |
| `AWAITING_CUSTOMER_RESPONSE` | cancel | `CANCELLED` |

## Customer Timeline

The customer sees four steps:

1. `PLACED`
2. `PACKED`
3. `ON_THE_WAY`
4. `ARRIVED`

This timeline is intentionally simpler than the internal order status enum.
