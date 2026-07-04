# Kafka Events

## Planned Consumed Topics

| Topic | Event | Purpose | Status |
|---|---|---|---|
| `checkout-events` | `CheckoutConfirmedEvent` | Create order from cart checkout | Pending |
| `order.arrived` | `OrderArrivedEvent` | Generate delivery verification code and update tracking | Pending |

## Planned Produced Topics

| Topic | Event | Trigger |
|---|---|---|
| `order-events` | `OrderCreatedEvent` | after order is created |
| `order-events` | `OrderStatusChangedEvent` | after status transition |
| `order-events` | `OrderCancelledEvent` | after cancellation |
| `order-events` | `OrderDeliveredEvent` | after delivery verification |

## Order Creation Event Flow

Planned flow:

1. Consume `CheckoutConfirmedEvent`.
2. Acquire `order:lock:{cartId}`.
3. Persist order in PostgreSQL.
4. Write Redis hot keys.
5. Publish order-created/status event.
6. Release lock.

## Current Implementation Status

Spring Kafka dependencies are present. Kafka consumers, producers, topic config, retry policy, and DLT handling are not implemented yet.

## Design Rule

Kafka handlers should be idempotent. Duplicate checkout events must not create duplicate orders for the same cart.
