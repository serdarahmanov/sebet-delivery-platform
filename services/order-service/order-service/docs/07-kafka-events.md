# Kafka Events

## Planned Consumed Topics

| Topic | Event | Purpose | Status |
|---|---|---|---|
| `checkout-events` | `CheckoutConfirmedEvent` | Create order from cart checkout | Consumer, DTO, mapper, Redis lock, DB creation, retry, and DLT handling implemented |
| `order.arrived` | `OrderArrivedEvent` | Generate delivery verification code and update tracking | Pending |
| `driver-location-events` | `DriverLocationUpdatedEvent` | Update Cache 3 (movementStatus, driverLat, driverLng, etaMinutes) and push WebSocket tracking update to customer | Pending |

## Planned Produced Topics

| Topic | Event | Trigger |
|---|---|---|
| `order-events` | `OrderCreatedEvent` | after order is created |
| `order-events` | `OrderStatusChangedEvent` | after status transition |
| `order-events` | `OrderCancelledEvent` | after cancellation |
| `order-events` | `OrderDeliveredEvent` | after delivery verification |

## Order Creation Event Flow

Target flow:

1. Consume `CheckoutConfirmedEvent`.
2. Acquire `order:lock:{cartId}`.
3. Map it to `CreateOrderCommand`.
4. Persist order in PostgreSQL.
5. Write Redis hot keys after commit.
6. Publish order-created/status event.
7. Release lock.

Implemented pieces:

- `CheckoutConfirmedEvent`
- `CheckoutConfirmedItem`
- `CheckoutDeliveryAddress`
- `CheckoutStoreLocation`
- `CheckoutConfirmedEventMapper`
- `CheckoutConfirmedEventConsumer`
- `CheckoutConfirmedEventProcessor`
- `OrderCreationService`
- `OrderCreationRedisWriter`

The mapper preserves item order, item-level discounts, order-level discounts, address snapshot, delivery coordinates, store coordinates, schedule type, and `cartId`.

Currently implemented in the flow:

- consume `CheckoutConfirmedEvent`
- acquire and release `order:lock:{cartId}` around order creation
- map it to `CreateOrderCommand`
- persist order, order items, and initial status history in PostgreSQL
- handle duplicate checkout events through `orders.cart_id` idempotency
- initialize the Redis snapshot, status, timeline, and active/scheduled membership after commit

## Current Implementation Status

Spring Kafka dependencies are present. The checkout event consumer is implemented and delegates to `CheckoutConfirmedEventProcessor`, which acquires the Redis checkout lock, maps the event, and calls `OrderCreationService`. It can consume real Kafka events when its listener startup property is enabled and `spring.kafka.bootstrap-servers` points to a broker.

Checkout event retry and DLT handling are implemented with Spring Kafka container infrastructure:

- failed listener processing is retried with configurable exponential backoff
- exhausted failures are published to the configured DLT topic with the original key
- DLT records are routed to the same partition number as the source record when the DLT topic has enough partitions
- deserialization is wrapped by `ErrorHandlingDeserializer` so malformed records can be routed through the error handler
- common permanent failures such as deserialization, conversion, validation, illegal argument, and data-integrity errors are marked non-retryable
- failed records are sought back after errors unless recovery succeeds, so a DLT publish failure does not mark the source record as handled

The listener is controlled by:

```yaml
order-service.kafka.checkout-events.auto-startup
```

It defaults to `false` in local configuration and `true` in production configuration.

Retry, DLT, and startup validation settings are controlled by:

```yaml
order-service.kafka.checkout-events.topic
order-service.kafka.checkout-events.dlt-topic
order-service.kafka.checkout-events.validate-topics
order-service.kafka.checkout-events.retry.initial-interval-ms
order-service.kafka.checkout-events.retry.multiplier
order-service.kafka.checkout-events.retry.max-interval-ms
order-service.kafka.checkout-events.retry.max-attempts
```

When `validate-topics` is enabled, startup checks that both the source topic and
DLT topic exist and that the DLT topic has at least as many partitions as the
source topic. This is enabled by default in the production profile.

DLT publish failure policy:

1. If listener processing fails and retries are exhausted, the error handler attempts DLT publish.
2. If DLT publish succeeds, the source offset can advance.
3. If DLT publish fails, the source record is not considered recovered and the source offset must not be committed.

This prevents losing a checkout event that was neither processed successfully nor stored in the DLT.

Kafka producers for business events and the delivery-arrival consumer are not implemented yet.

Concurrent checkout creation is guarded by `order:lock:{cartId}`. If another service instance already holds that lock, processing fails with a retryable exception so Spring Kafka can redeliver the record according to the configured retry policy. Duplicate checkout creation is still protected at the database level by the unique `orders.cart_id` index. `OrderCreationService` also returns the existing order when the cart id has already been processed.

If a replay happens after a partial Redis failure, the cache write-through path is idempotent and rebuilds missing hot-view keys from the current database order state instead of replaying stale checkout payload data.

The Redis lock owner value comes from `order-service.instance-id`. If it is not configured, the service uses `${spring.application.name}-${random.uuid}`, which gives each running replica a distinct owner value.

The checkout processor is covered by unit tests for successful creation, duplicate handling, failure release, lock contention, release failure, and concurrent duplicate checkout events. The Kafka listener is covered by integration tests that start real Kafka brokers, PostgreSQL databases, and Redis with Testcontainers. Current coverage includes successful order creation, retryable failures to DLT, non-retryable failures to DLT without retry, malformed JSON deserialization failures to DLT, partition/key preservation, DLT publish failure behavior, and retry property binding.

## Design Rule

Kafka handlers should be idempotent. Duplicate checkout events must not create duplicate orders for the same cart.
