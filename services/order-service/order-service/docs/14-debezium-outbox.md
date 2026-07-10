# Debezium Outbox

Order-service publishes business events through the PostgreSQL `outbox_event`
table. The application inserts outbox rows in the same transaction as order
state changes. Debezium reads the database log and publishes those rows to
Kafka.

## Deployment Decision

Debezium runs as an **external Kafka Connect connector**, not as an embedded
engine inside order-service.

Consequence: **order-service contains no outbox relay code**. The service's
only responsibility is writing rows into `outbox_event` via
`OrderEventOutboxWriter`, which is already complete. The connector is deployed
and operated independently of the application. Do not add a `KafkaTemplate`
publisher, a polling `@Scheduled` relay, or an embedded `DebeziumEngine` to
this service.

Sample connector configuration:

```text
debezium/order-service-outbox-connector.json
```

## Runtime Contract

Source table:

```text
public.outbox_event
```

Kafka topic:

```text
order-events
```

Kafka key:

```text
outbox_event.event_key
```

Kafka value:

```text
outbox_event.payload
```

Kafka headers:

```text
id
eventType
aggregateType
aggregateId
```

The `id` header is emitted by the Debezium outbox router from the event id
column. The other headers are mapped from outbox metadata columns.

## Connector Shape

The connector should capture only the outbox table:

```json
{
  "table.include.list": "public.outbox_event"
}
```

The Outbox Event Router SMT maps this service's column names to Debezium's
outbox concepts:

```json
{
  "transforms": "outbox",
  "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
  "transforms.outbox.table.field.event.id": "id",
  "transforms.outbox.table.field.event.key": "event_key",
  "transforms.outbox.table.field.event.payload": "payload",
  "transforms.outbox.table.field.event.timestamp": "occurred_at",
  "transforms.outbox.route.topic.replacement": "order-events"
}
```

`payload` is stored as PostgreSQL `jsonb`. The sample connector sets:

```json
{
  "value.converter": "org.apache.kafka.connect.json.JsonConverter",
  "value.converter.schemas.enable": "false",
  "transforms.outbox.table.expand.json.payload": "true"
}
```

This keeps the Kafka value as the event envelope JSON object rather than a
Debezium row-change envelope.

## Snapshot Mode

The sample uses:

```json
{
  "snapshot.mode": "no_data"
}
```

This is intentional for normal production rollout: the connector should publish
new outbox rows from the log, not replay old rows already present in the table.
If the connector is introduced after outbox rows already exist and those rows
must be published, use an explicit backfill/replay plan instead of silently
changing snapshot behavior.

## PostgreSQL Requirements

Use the PostgreSQL `pgoutput` logical decoding plug-in:

```json
{
  "plugin.name": "pgoutput"
}
```

Each connector instance needs its own replication slot:

```json
{
  "slot.name": "order_service_outbox"
}
```

For `pgoutput`, Debezium recommends filtered publication creation when the
connector captures a subset of tables:

```json
{
  "publication.autocreate.mode": "filtered"
}
```

The Debezium database user needs replication privileges and enough privileges
to create or use the configured publication and slot. In production, prefer
pre-creating the slot/publication or granting only the minimum operational
permissions required by the platform.

## Consumer Idempotency

Debezium and Kafka should be treated as at-least-once delivery. Consumers must
be idempotent.

Use:

```text
eventId / id header -> exact event dedupe
payload.version     -> stale or out-of-order aggregate protection
```

Consumer-side database writes should use an inbox table, unique business keys,
or aggregate version checks depending on the consumer's behavior.

## Produced Event Types

Order-service emits specialized event types rather than a generic
status-changed event:

```text
OrderCreated
OrderScheduled
OrderActivated
OrderAccepted
OrderCancelled
OrderReadyForPickup
OrderPickedUp
OrderArrived
OrderDelivered
DriverAssigned
DriverReplaced
DriverUnassigned
DriverAssignmentDeclined
OrderCacheEvictionRequested
```

`OrderCancelled` is the canonical cancellation event for all cancellation
sources. Consumers should use payload data such as `cancelledBy`,
`cancellationReason`, `previousStatus`, and `newStatus` to distinguish the
business reason.

`DriverAssigned`, `DriverReplaced`, and `DriverUnassigned` are emitted by the
internal dispatch-facing assignment endpoints. They are not status-transition
events; the order status remains unchanged.

Payload `data` shapes:

```json
{
  "orderId": "order-id",
  "customerId": "customer-id",
  "storeId": "store-id",
  "driverId": "driver-id",
  "status": "READY_FOR_PICKUP",
  "assignedAt": "2026-07-08T10:15:00Z"
}
```

```json
{
  "orderId": "order-id",
  "customerId": "customer-id",
  "storeId": "store-id",
  "previousDriverId": "old-driver-id",
  "newDriverId": "new-driver-id",
  "status": "READY_FOR_PICKUP",
  "replacedAt": "2026-07-08T10:15:00Z"
}
```

```json
{
  "orderId": "order-id",
  "customerId": "customer-id",
  "storeId": "store-id",
  "previousDriverId": "driver-id",
  "status": "READY_FOR_PICKUP",
  "unassignedAt": "2026-07-08T10:15:00Z",
  "reason": "ADMIN_OVERRIDE"
}
```

`DriverAssignmentDeclined` is emitted when an assigned driver declines before
pickup. It is not a status-transition event; the order status remains unchanged
and the order becomes available for reassignment.

Payload `data` shape:

```json
{
  "orderId": "order-id",
  "customerId": "customer-id",
  "storeId": "store-id",
  "driverId": "driver-id-that-declined",
  "status": "READY_FOR_PICKUP",
  "declinedAt": "2026-07-08T10:15:00Z",
  "reason": "DRIVER_DECLINED"
}
```

`OrderCacheEvictionRequested` is emitted only when direct Redis eviction fails
because Redis is unavailable or the Redis command result is unknown after a
committed write that requires explicit cache cleanup. Current producers include
driver assignment/decline C2 eviction and store-cancel grouped hot-view eviction.
It is a technical event for order-service cache maintenance, not a business
lifecycle event.

Payload `data` shape:

```json
{
  "orderId": "order-id",
  "cacheName": "C2",
  "cacheKey": "order:order-id",
  "cacheKeys": ["order:order-id"],
  "reason": "DRIVER_ASSIGNMENT_CHANGED",
  "sourceAction": "INTERNAL_ASSIGN_DRIVER",
  "idempotencyKey": "idempotency-key",
  "failureType": "RedisConnectionFailureException",
  "failureMessage": "redis unavailable",
  "requestedAt": "2026-07-08T10:15:00Z"
}
```

## Payload Scope

Current order event payloads are intentionally minimal. They carry stable
identity, lifecycle, pricing summary, and status-transition metadata, but they
do not try to include a full order snapshot yet.

For example, `OrderCreated` and `OrderScheduled` currently do not include:

```text
order items
delivery address snapshot
delivery coordinates
store coordinates
promotion or discount breakdown details
```

This is deliberate for the current stage. Payloads should grow only when a real
consumer has a clear need for additional fields. When adding fields, keep the
top-level envelope stable and evolve the `data` object in a backward-compatible
way.

## Cleanup

Debezium does not delete outbox rows after publishing. The platform needs a
retention policy, for example deleting rows older than an agreed interval after
connector lag is confirmed healthy.

Do not delete recent outbox rows blindly. A lagging connector still needs the
PostgreSQL WAL, but retaining rows is useful for audit, operational inspection,
and manual replay planning.

Recommended approach:

```text
Kubernetes CronJob or equivalent platform job
```

Prefer a platform job over an in-process Spring `@Scheduled` cleanup. Cleanup is
operational maintenance, not order business logic, and running it outside the
application avoids every service replica needing coordination or distributed
locks.

The cleanup job should perform these checks before deleting rows:

1. Kafka Connect connector is healthy.
2. PostgreSQL replication slot lag is below the accepted threshold.
3. The configured retention window is acceptable for audit and manual replay.

Kafka Connect health check:

```http
GET /connectors/order-service-outbox/status
```

The job should require the connector and every task to be `RUNNING`.

PostgreSQL replication lag check:

```sql
select
  slot_name,
  pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn) as lag_bytes
from pg_replication_slots
where slot_name = 'order_service_outbox';
```

If `lag_bytes` is above the platform threshold, the cleanup job must skip
deletion.

Example cleanup statement:

```sql
delete from outbox_event
where created_at < now() - interval '14 days';
```

The retention interval should be configurable per environment. The job should
log connector state, replication lag, retention cutoff, and deleted row count on
every run.
