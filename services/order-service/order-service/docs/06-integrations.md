# Integrations

## Cart Service

Planned inbound integration:

```text
CheckoutConfirmedEvent
```

Source topic:

```text
checkout-events
```

Order-service consumes this event, guards order creation with `order:lock:{cartId}`, creates the durable order, writes an order-created outbox event in the same database transaction, and initializes Redis hot views after the transaction commits from the current database order state. Debezium runtime deployment is still pending.

## Delivery Service

The driver arrival flow is now handled via the driver REST API (`POST /arrive`), not a Kafka consumer. When the driver marks arrival:

- Status transitions `OUT_FOR_DELIVERY -> ARRIVED`
- A 2-digit verification code is generated, written to C7, and persisted to `order_status_history.metadata_json`
- The customer reads the code from `GET /api/v1/orders/{orderId}/verification-code`

A planned inbound Kafka integration remains for cases where arrival is detected externally:

```text
order.arrived
```

This consumer would replicate the same arrive logic when triggered by a delivery tracking service rather than the driver app.

## Driver/Delivery App

Driver-facing REST endpoints are defined in `DriverOrderController` under `/api/v1/driver/orders`. All requests require an `X-Driver-Id` header, enforced by `DriverIdInterceptor`.

Implemented:

```text
GET  /api/v1/driver/orders/{orderId}           - delivery detail (C2 + C4, DB fallback)
POST /api/v1/driver/orders/{orderId}/pickup    - READY_FOR_PICKUP -> OUT_FOR_DELIVERY
POST /api/v1/driver/orders/{orderId}/arrive    - OUT_FOR_DELIVERY -> ARRIVED; generates verification code -> C7 + DB
POST /api/v1/driver/orders/{orderId}/complete  - ARRIVED -> DELIVERED; validates code from C7 with DB fallback
POST /api/v1/driver/orders/{orderId}/decline   - unassigns driver; valid only before OUT_FOR_DELIVERY
```

`/decline` requires an `Idempotency-Key`. If the order/outbox write commits but
direct C2 eviction fails because Redis is unavailable or the Redis command
result is unknown, order-service records `OrderCacheEvictionRequested` and still
returns `200`. Non-Redis runtime failures propagate normally. The endpoint
returns `503 CACHE_INVALIDATION_FAILED` only when that fallback event cannot be
recorded.

GPS and ETA updates do not go through this API. The driver app sends coordinates to the tracking service, which publishes a `DriverLocationUpdatedEvent`. Order-service consumes that event, updates Cache 3 (`movementStatus`, `driverLat`, `driverLng`, `etaMinutes`), and pushes a WebSocket message to the customer.

## Dispatch Service

The dispatch service assigns and unassigns drivers via the internal REST API. All requests require the `X-Internal-Key` header, enforced by `InternalAuthInterceptor`.
Driver assignment writes also require `Idempotency-Key`.

Implemented endpoints:

```text
POST /api/v1/internal/orders/{orderId}/assign-driver    - sets driverId + driverAssignedAt;
                                                          idempotent on same driverId,
                                                          emits DriverAssigned or DriverReplaced
POST /api/v1/internal/orders/{orderId}/unassign-driver  - clears driverId; valid on any
                                                          non-terminal status; emits
                                                          DriverUnassigned
```

Driver assignment writes update PostgreSQL, `outbox_event`, and the idempotent
command record in one transaction, then try to evict C2 after commit. If Redis
is unavailable or the Redis command result is unknown, order-service records
`OrderCacheEvictionRequested` and still returns `200`. Non-Redis runtime
failures propagate normally. The endpoint returns `503 CACHE_INVALIDATION_FAILED`
only when direct eviction fails with a recoverable Redis failure and the fallback
event cannot be recorded. Same-key retries replay the stored response and repeat
the eviction path, so duplicate assignment events are not emitted.

The `OrderCacheEvictionProjectionConsumer` also listens to `order-events` for
`OrderCacheEvictionRequested`. It dispatches those deliberate cache eviction
events by `data.cacheName`, currently including C2 and
`CANCELLED_ORDER_HOT_VIEWS`. General driver assignment events are ignored by this
consumer, so a delayed `DriverAssigned`/`DriverReplaced`/`DriverUnassigned`/
`DriverAssignmentDeclined` event does not delete a freshly rebuilt C2 snapshot.
The consumer uses MANUAL ack mode: if Redis is unavailable or the Redis command
times out during eviction, the container pauses and the offset is not committed.
`RedisRecoveryScheduler` probes Redis every 10 seconds and resumes the container
when Redis responds.

Admin/ops also use the internal API to force lifecycle transitions:

```text
POST /api/v1/internal/orders/{orderId}/system-cancel       - system-initiated cancellation
POST /api/v1/internal/orders/{orderId}/admin-cancel        - admin override cancellation
POST /api/v1/internal/orders/{orderId}/activate-scheduled  - SCHEDULED -> PENDING
POST /api/v1/internal/orders/{orderId}/cancel-active-proposal
                                                          - cancel proposal only
POST /api/v1/internal/orders/{orderId}/cancel-proposal-and-order
                                                          - AWAITING_CUSTOMER_RESPONSE -> CANCELLED
```

`activate-scheduled` is a manual admin/support override and requires `Idempotency-Key`.
The automatic scheduled-order transition job is implemented separately. It
activates only `SCHEDULED` orders whose `scheduledFor` is within the configured
lead-time window. `cancel-proposal-and-order` is likewise available as a manual
internal transition, with a separate automatic `ProposalTimeoutScheduler` job
that cancels `AWAITING_CUSTOMER_RESPONSE` orders once the configured customer
response window has elapsed. ShedLock uses PostgreSQL to ensure only one service
replica runs the scheduled activation job or the proposal timeout job at a time.
The REST idempotent command cleanup and processed checkout event cleanup
schedulers use the same PostgreSQL-backed lock pattern to avoid duplicate
cleanup work across replicas.

`system-cancel` requires `Idempotency-Key` and accepts only system-owned reasons:
`PAYMENT_FAILED`, `NO_RIDERS_AVAILABLE`, `STORE_RESPONSE_TIMEOUT`,
`AWAITING_CUSTOMER_RESPONSE_TIMEOUT`, and `SYSTEM_ERROR`. It writes the
cancellation, lifecycle outbox event, and idempotent command response in one
transaction, then uses the cancelled hot-view fallback pattern to remove C1/C1b
membership and delete C2/C3/C4/C6 after commit and on idempotent replay.
`admin-cancel` uses the same payload and Redis cleanup pattern, but is reserved
for admin/support override and can cancel any order that is not `DELIVERED` or
already `CANCELLED`.

Proposal cancellation has two separate internal meanings. `cancel-active-proposal`
will remove the active proposal without cancelling the order, so a corrected
proposal can be submitted later. `cancel-proposal-and-order` will close the
proposal and cancel the order.

Store and internal `cancel-active-proposal` are implemented and require
`Idempotency-Key`. The store endpoint also verifies `X-Store-Id` ownership. The
action moves `AWAITING_CUSTOMER_RESPONSE -> CONFIRMED`, marks the durable
proposal row as `CANCELLED`, emits `OrderActiveProposalCancelled`, deletes C8,
updates C4 to `CONFIRMED`, and removes `AWAITING_CUSTOMER_RESPONSE` entries from
C6 with one Redis Lua script.

## Frontend Clients

Customer clients use:

- REST for initial order lists/details.
- planned WebSocket/STOMP for live tracking updates.

Store clients use:

- REST for kitchen dashboard and store actions.
- planned WebSocket/STOMP for live dashboard updates.

## Integration Status

The checkout event consumer and Redis hot-view write-through path are implemented. Customer read and write services are implemented. Store read services are implemented. Store `accept`, `reject`, `ready`, `cancel`, `propose-changes`, and `cancel-active-proposal` actions are implemented through the shared lifecycle service. Store `/cancel`, `/propose-changes`, and `/cancel-active-proposal` require `Idempotency-Key` and use recoverable Redis hot-view update or eviction patterns after the database transaction. Driver detail and lifecycle actions (`detail`, `pickup`, `arrive`, `complete`, `decline`) are implemented through `DriverOrderLifecycleService`. Internal driver assignment actions (`assign-driver`, `unassign-driver`) are implemented through `InternalDriverAssignmentService`; internal lifecycle actions (`activate-scheduled`, `system-cancel`, `admin-cancel`, `cancel-active-proposal`, `cancel-proposal-and-order`, `update-after-proposal`) are implemented through `InternalOrderLifecycleService` and require `Idempotency-Key`. Automatic scheduled-order activation is implemented through `ScheduledOrderActivationScheduler` and guarded by PostgreSQL-backed ShedLock. Automatic proposal timeout cancellation is implemented through `ProposalTimeoutScheduler` using the same ShedLock pattern. Order-created, lifecycle, proposal, and driver-assignment business events are written to `outbox_event` for Debezium publishing. Deliberate `OrderCacheEvictionRequested` events can also be consumed back by order-service to repair Redis hot views after recoverable Redis direct-update failures. `DriverIdInterceptor` and `InternalAuthInterceptor` enforce identity headers. Delivery tracking integration, deployment wiring, a store response timeout job, and WebSocket broker are pending.
