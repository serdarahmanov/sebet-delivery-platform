# Kafka Events

Cart-service consumes projection events and publishes checkout confirmation events.

## Consumed Topics

Configured under:

```yaml
app:
  kafka:
    topics:
```

Default topics:

- `product-events`
- `inventory-events`
- `store-events`

## Projection Consumers

Product events:

```text
ProductEventListener
ProductEventHandler
ProductProjection
ProductProjectionRepository
```

Inventory events:

```text
InventoryEventListener
InventoryEventHandler
InventoryProjection
InventoryProjectionRepository
```

Store events:

```text
StoreEventListener
StoreEventHandler
StoreProjection
StoreProjectionRepository
```

Handlers update PostgreSQL projection tables used by cart validation.

Projection handlers use optimistic version checks. If an incoming event version is older than or equal to the stored projection version, the event is treated as stale and dropped.

## Published Events

Checkout confirmation publishes:

```text
CheckoutConfirmed
```

Publisher:

```text
CheckoutEventPublisher
```

Default topic:

```text
checkout-events
```

## Retry and DLT Behavior

`KafkaConsumerConfig` configures:

- `DefaultErrorHandler`
- fixed backoff of 1 second
- 3 retries after the first processing attempt
- `DeadLetterPublishingRecoverer`
- DLT topic pattern: `{originalTopic}.DLT`
- recovered records are committed

Malformed Jackson messages are not retried and are sent directly to DLT.

## DLT Metrics

Kafka DLT publish success and failure are recorded through `KafkaMetrics`.

DLT publish success is counted only after the DLT publish call returns successfully.

## Event Handling Rule

Unsupported or invalid product event types are ignored safely when they represent non-actionable input. Real processing failures still propagate to Kafka error handling.
