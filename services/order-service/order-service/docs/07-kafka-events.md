# Kafka Events

## Planned Consumed Topics

| Topic | Event | Purpose | Status |
|---|---|---|---|
| `checkout-events` | `CheckoutConfirmed` envelope | Create order from cart checkout | Raw-string consumer, envelope DTOs, handler validation, processed-event idempotency, Redis lock, DB creation, retry, and DLT handling implemented |
| `order.arrived` | `OrderArrivedEvent` | Generate delivery verification code and update tracking | Pending |
| `driver-location-events` | `DriverLocationUpdatedEvent` | Update Cache 3 (movementStatus, driverLat, driverLng, etaMinutes) and push WebSocket tracking update to customer | Pending |

## Produced Topics

| Topic | Event | Trigger |
|---|---|---|
| `order-events` | `OrderCreated` | after an ASAP order is created |
| `order-events` | `OrderScheduled` | after a scheduled order is created |
| `order-events` | `OrderActivated` | after a scheduled order enters the live queue |
| `order-events` | `OrderAccepted` | after store acceptance |
| `order-events` | `OrderCancelled` | after any cancellation |
| `order-events` | `OrderReadyForPickup` | after store marks the order ready |
| `order-events` | `OrderPickedUp` | after driver pickup |
| `order-events` | `OrderArrived` | after driver arrival |
| `order-events` | `OrderDelivered` | after delivery verification |
| `order-events` | `DriverAssigned` | after an order receives its first driver assignment |
| `order-events` | `DriverReplaced` | after an assigned driver is replaced by another driver |
| `order-events` | `DriverUnassigned` | after an internal caller removes the assigned driver |
| `order-events` | `DriverAssignmentDeclined` | after an assigned driver declines before pickup |
| `order-events` | `OrderCacheEvictionRequested` | after direct C2 eviction fails because Redis is unavailable or the Redis result is unknown |

Order-service records produced events in the local `outbox_event` table. Debezium
is responsible for reading the database transaction log and publishing those
rows to Kafka. The application does not publish order business events directly
with `KafkaTemplate`.

See [Debezium Outbox](14-debezium-outbox.md) for the connector contract and
sample configuration.

Outbox table columns:

```text
id
aggregate_type
aggregate_id
event_type
event_key
payload
headers
occurred_at
created_at
```

`id` is the event id. `event_key` is the Kafka key and should be used for
partitioning; for order events it is the order id. The canonical event version
lives in `payload.version`, not in a separate table column.

Produced event envelope:

```json
{
  "eventId": "uuid",
  "eventType": "OrderCreated",
  "aggregateId": "order-id",
  "aggregateType": "Order",
  "version": 1,
  "occurredAt": "2026-07-08T10:00:00Z",
  "source": "order-service",
  "data": {}
}
```

Consumers should treat delivery as at-least-once. Use `eventId` for exact-event
deduplication and `version` for stale or out-of-order aggregate protection when
maintaining projections.

## Order Creation Event Flow

Target flow:

1. Consume the raw JSON value from `checkout-events`.
2. Deserialize it as `IntegrationEvent<CheckoutConfirmedPayload>`.
3. Validate the envelope and payload contract.
4. Skip if `processed_events.event_id` already exists.
5. Acquire `order:lock:{cartId}`.
6. Map it to `CreateOrderCommand`.
7. Persist order in PostgreSQL.
8. Insert the checkout `eventId` into `processed_events` after successful order creation and Redis initialization.
9. Insert `OrderCreated` or `OrderScheduled` into `outbox_event` in the same PostgreSQL transaction.
10. Write Redis hot keys after commit.
11. Release lock.

Implemented pieces:

- `IntegrationEvent<T>`
- `CheckoutConfirmedPayload`
- `MoneyBreakdown`
- `DeliverySnapshot`
- `StoreLocationSnapshot`
- `CheckoutItemPayload`
- `CheckoutScheduleType`
- `CheckoutConfirmedEventMapper`
- `CheckoutConfirmedEventConsumer`
- `CheckoutConfirmedHandler`
- `OrderCreationService`
- `OrderCreationRedisWriter`
- `ProcessedEventRepository`
- `OrderEventOutboxWriter`
- `OutboxEventRepository`

The mapper preserves item order, item gross/discount/net amounts, order-level totals, address snapshot, store location snapshot, schedule type, and `cartId`.

Currently implemented in the flow:

- consume raw JSON checkout envelopes from `checkout-events`
- deserialize `IntegrationEvent<CheckoutConfirmedPayload>` manually with Jackson
- validate event id, type, version, aggregate type/id, money, snapshots, items, and schedule rules
- acquire and release `order:lock:{cartId}` around order creation
- map it to `CreateOrderCommand`
- persist order, order items, and initial status history in PostgreSQL
- insert `OrderCreated` or `OrderScheduled` into `outbox_event` in the same PostgreSQL transaction
- insert specialized lifecycle and assignment events into `outbox_event` in the same PostgreSQL transaction
- handle duplicate checkout events through `processed_events.event_id` and `orders.cart_id` idempotency, while recording the processed event only after Redis initialization succeeds
- initialize the Redis snapshot, status, timeline, and active/scheduled membership after commit

## Current Implementation Status

Spring Kafka dependencies are present. The checkout event consumer is implemented as a raw `String` listener and delegates valid envelopes to `CheckoutConfirmedHandler`, which enforces idempotency, acquires the Redis checkout lock, maps the event, and calls `OrderCreationService`. It can consume real Kafka events when its listener startup property is enabled and `spring.kafka.bootstrap-servers` points to a broker.

The cache-eviction projection consumer is also implemented as a raw `String`
listener on `order-events`. It handles only `OrderCacheEvictionRequested` with
`data.cacheName`. The handler dispatches to the registered `CacheEvictionStrategy`
for that cache name; the strategy performs the eviction so the next Redis-first
read rebuilds from PostgreSQL. General driver assignment events are
ignored by this consumer to avoid deleting a C2 snapshot that was already
successfully evicted and then rebuilt.

The consumer uses a dedicated `cacheEvictionContainerFactory` with `MANUAL` ack
mode. On success the offset is committed immediately. On
`RedisConnectionFailureException` or `QueryTimeoutException`, the container is
paused and the offset is not committed, so the same message is replayed when the
container resumes. `RedisRecoveryScheduler` probes Redis every 10 seconds using
a dedicated connection with a 500 ms timeout; when Redis responds, the container
is resumed automatically.

Checkout event retry and DLT handling are implemented with Spring Kafka container infrastructure:

- failed listener processing is retried with configurable exponential backoff
- exhausted failures are published to the configured DLT topic with the original key
- DLT records are routed to the same partition number as the source record when the DLT topic has enough partitions
- deserialization is string-based and malformed checkout JSON is surfaced as listener failure so it can be routed through the error handler
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

Driver-assignment projection listener settings are controlled by:

```yaml
order-service.kafka.order-events.topic
order-service.kafka.order-events.group-id
order-service.kafka.order-events.auto-startup
order-service.kafka.order-events.dlt-topic
order-service.kafka.order-events.validate-topics
```

When `validate-topics` is enabled for either consumed topic, startup checks that
both the source topic and DLT topic exist and that the DLT topic has at least as
many partitions as the source topic. This is enabled by default in the
production profile.

DLT publish failure policy:

1. If listener processing fails and retries are exhausted, the error handler attempts DLT publish.
2. If DLT publish succeeds, the source offset can advance.
3. If DLT publish fails, the source record is not considered recovered and the source offset must not be committed.

This prevents losing a checkout event that was neither processed successfully nor stored in the DLT.

Direct Kafka producers for business events are intentionally not implemented.
Produced order business events are written to `outbox_event` for Debezium
publishing. The delivery-arrival consumer is not implemented yet.

Concurrent checkout creation is guarded by `order:lock:{cartId}`. If another service instance already holds that lock, processing fails with a retryable exception so Spring Kafka can redeliver the record according to the configured retry policy. Duplicate checkout creation is still protected at the database level by the unique `orders.cart_id` index. `OrderCreationService` also returns the existing order when the cart id has already been processed.

If a replay happens after a partial Redis failure, the cache write-through path is idempotent and rebuilds missing hot-view keys from the current database order state instead of replaying stale checkout payload data.

The Redis lock owner value comes from `order-service.instance-id`. If it is not configured, the service uses `${spring.application.name}-${random.uuid}`, which gives each running replica a distinct owner value.

The checkout handler is covered by unit tests for successful creation, processed-event duplicate handling, duplicate cart handling, validation failures, failure release, lock contention, and release failure. The Kafka listener is covered by integration tests that start real Kafka brokers, PostgreSQL databases, and Redis with Testcontainers. Current coverage includes successful order creation, retryable failures to DLT, non-retryable failures to DLT without retry, malformed JSON failures to DLT, partition/key preservation, DLT publish failure behavior, and retry property binding. The cache-eviction projection consumer is covered by unit tests for raw event deserialization, unsupported event skipping, general driver-event skipping, C2 eviction for `OrderCacheEvictionRequested`, Redis failure pause behavior, and acknowledgment contract.

## Design Rule

Kafka handlers should be idempotent. Duplicate checkout events must not create duplicate orders for the same cart.
