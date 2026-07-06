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

Order-service consumes this event, guards order creation with `order:lock:{cartId}`, creates the durable order, and handles retry/DLT failures. Redis hot-view initialization and order-created/status publishing are still pending.

## Delivery Service

Planned inbound delivery integration:

```text
order.arrived
```

On arrival, order-service should generate a delivery verification code, update status/timeline, write C7, and push live updates to the customer.

## Driver/Delivery App

Planned driver endpoint:

```text
POST /api/v1/driver/orders/{orderId}/verify-code
```

This endpoint should validate the delivery code and transition the order to `DELIVERED`.

## Frontend Clients

Customer clients use:

- REST for initial order lists/details.
- planned WebSocket/STOMP for live tracking updates.

Store clients use:

- REST for kitchen dashboard and store actions.
- planned WebSocket/STOMP for live dashboard updates.

## Integration Status

The contracts are documented in controllers and DTOs. The checkout event consumer is implemented. The delivery-arrival consumer, event publishers, driver endpoints, and WebSocket broker are pending.
