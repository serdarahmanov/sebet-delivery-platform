# Order Lifecycle

## Normal Flow

```text
PENDING
  -> CONFIRMED
  -> READY_FOR_PICKUP
  -> DRIVER_ASSIGNED
  -> OUT_FOR_DELIVERY
  -> ARRIVED
  -> DELIVERED
```

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
