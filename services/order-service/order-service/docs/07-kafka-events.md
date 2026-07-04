# Kafka Events

## Planned Consumed Topics

| Topic | Event | Purpose | Status |
|---|---|---|---|
| `checkout-events` | `CheckoutConfirmedEvent` | Create order from cart checkout | DTO and mapper implemented; consumer pending |
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
2. Map it to `CreateOrderCommand`.
3. Acquire `order:lock:{cartId}`.
4. Persist order in PostgreSQL.
5. Write Redis hot keys.
6. Publish order-created/status event.
7. Release lock.

Implemented pieces:

- `CheckoutConfirmedEvent`
- `CheckoutConfirmedItem`
- `CheckoutDeliveryAddress`
- `CheckoutStoreLocation`
- `CheckoutConfirmedEventMapper`
- `OrderCreationService`

The mapper preserves item order, item-level discounts, order-level discounts, address snapshot, delivery coordinates, store coordinates, schedule type, and `cartId`.

## Current Implementation Status

Spring Kafka dependencies are present. Kafka consumers, producers, topic config, retry policy, and DLT handling are not implemented yet.

Duplicate checkout creation is protected at the database level by the unique `orders.cart_id` index. `OrderCreationService` also returns the existing order when the cart id has already been processed.

## Design Rule

Kafka handlers should be idempotent. Duplicate checkout events must not create duplicate orders for the same cart.
