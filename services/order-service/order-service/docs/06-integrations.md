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

Order-service should consume this event, create the durable order, initialize Redis keys, and publish order-created/status events.

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

The contracts are documented in controllers and DTOs. The event consumers, event publishers, driver endpoints, and WebSocket broker are pending.
