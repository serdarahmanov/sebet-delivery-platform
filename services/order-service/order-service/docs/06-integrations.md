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
`OrderCacheEvictionRequested`. It evicts C2 for those deliberate cache eviction
events. General driver assignment events are ignored by this consumer, so a
delayed `DriverAssigned`/`DriverReplaced`/`DriverUnassigned`/
`DriverAssignmentDeclined` event does not delete a freshly rebuilt C2 snapshot.
The consumer uses MANUAL ack mode: if Redis is unavailable or the Redis command
times out during eviction, the container pauses and the offset is not committed.
`RedisRecoveryScheduler` probes Redis every 10 seconds and resumes the container
when Redis responds.

Admin/ops also use the internal API to force lifecycle transitions:

```text
POST /api/v1/internal/orders/{orderId}/system-cancel       - system-initiated cancellation
POST /api/v1/internal/orders/{orderId}/activate-scheduled  - SCHEDULED -> PENDING
POST /api/v1/internal/orders/{orderId}/cancel-proposal     - AWAITING_CUSTOMER_RESPONSE -> CANCELLED
```

## Frontend Clients

Customer clients use:

- REST for initial order lists/details.
- planned WebSocket/STOMP for live tracking updates.

Store clients use:

- REST for kitchen dashboard and store actions.
- planned WebSocket/STOMP for live dashboard updates.

## Integration Status

The checkout event consumer and Redis hot-view write-through path are implemented. Customer read services are implemented. Store read services are implemented. Store `accept`, `reject`, and `ready` actions are implemented through the shared lifecycle service. Driver detail and lifecycle actions (`detail`, `pickup`, `arrive`, `complete`, `decline`) are implemented through `DriverOrderLifecycleService`. Internal driver assignment actions (`assign-driver`, `unassign-driver`) are implemented through `InternalDriverAssignmentService`. Order-created, lifecycle, and driver-assignment business events are written to `outbox_event` for Debezium publishing. Deliberate `OrderCacheEvictionRequested` events can also be consumed back by order-service to evict C2 Redis snapshots after recoverable Redis direct-eviction failures. `DriverIdInterceptor` and `InternalAuthInterceptor` enforce identity headers. The remaining store write methods and remaining internal lifecycle service methods are pending. The delivery-arrival Kafka consumer, Debezium runtime deployment, and WebSocket broker are pending.
