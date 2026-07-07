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

Order-service consumes this event, guards order creation with `order:lock:{cartId}`, creates the durable order, and initializes Redis hot views after the transaction commits from the current database order state. Order-created/status publishing is still pending.

## Delivery Service

Planned inbound delivery integration:

```text
order.arrived
```

On arrival, order-service should generate a delivery verification code, update status/timeline, write C7, and push live updates to the customer.

## Driver/Delivery App

Driver-facing REST endpoints are defined in `DriverOrderController` under `/api/v1/driver/orders`. All requests require an `X-Driver-Id` header, enforced by `DriverIdInterceptor`.

Implemented endpoint contracts (stubs — service layer pending):

```text
GET  /api/v1/driver/orders/{orderId}           — delivery detail (snapshot + current status)
POST /api/v1/driver/orders/{orderId}/pickup    — READY_FOR_PICKUP → OUT_FOR_DELIVERY
POST /api/v1/driver/orders/{orderId}/arrive    — OUT_FOR_DELIVERY → ARRIVED; generates verification code → C7
POST /api/v1/driver/orders/{orderId}/complete  — ARRIVED → DELIVERED; validates code from C7
POST /api/v1/driver/orders/{orderId}/decline   — unassigns driver; valid only before OUT_FOR_DELIVERY
```

GPS and ETA updates do not go through this API. The driver app sends coordinates to the tracking service, which publishes a `DriverLocationUpdatedEvent`. Order-service consumes that event, updates Cache 3 (`movementStatus`, `driverLat`, `driverLng`, `etaMinutes`), and pushes a WebSocket message to the customer.

## Dispatch Service

The dispatch service assigns and unassigns drivers via the internal REST API. All requests require the `X-Internal-Key` header, enforced by `InternalAuthInterceptor`.

Implemented endpoint contracts (stubs — service layer pending):

```text
POST /api/v1/internal/orders/{orderId}/assign-driver    — sets driverId + driverAssignedAt;
                                                          idempotent on same driverId,
                                                          publishes DriverReplacedEvent on change
POST /api/v1/internal/orders/{orderId}/unassign-driver  — clears driverId; valid on any
                                                          non-terminal status; publishes
                                                          DriverUnassignedEvent
```

Admin/ops also use the internal API to force lifecycle transitions:

```text
POST /api/v1/internal/orders/{orderId}/system-cancel       — system-initiated cancellation
POST /api/v1/internal/orders/{orderId}/activate-scheduled  — SCHEDULED → PENDING
POST /api/v1/internal/orders/{orderId}/cancel-proposal     — AWAITING_CUSTOMER_RESPONSE → CANCELLED
```

## Frontend Clients

Customer clients use:

- REST for initial order lists/details.
- planned WebSocket/STOMP for live tracking updates.

Store clients use:

- REST for kitchen dashboard and store actions.
- planned WebSocket/STOMP for live dashboard updates.

## Integration Status

The contracts are documented in controllers and DTOs. The checkout event consumer and Redis hot-view write-through path are implemented. Customer read services are implemented. Store `accept`, `reject`, and `ready` actions are implemented through the shared lifecycle service. Driver endpoint contracts (`DriverOrderController`) and `DriverIdInterceptor` are implemented. Internal endpoint contracts (`InternalOrderController`) and `InternalAuthInterceptor` are implemented. Store read services and the remaining store write methods, all driver service methods, and all internal service methods are pending. The delivery-arrival consumer, event publishers, and WebSocket broker are pending.
