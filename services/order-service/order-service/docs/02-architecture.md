# Architecture

## High-Level Shape

Order-service is designed as an event-entry microservice.

Orders are not created directly by REST. The implemented entry point is a Kafka `CheckoutConfirmedEvent` from cart-service. After creation, customer and store clients interact with the order through REST APIs and, later, WebSocket/STOMP live updates.

```text
Kafka checkout event
  -> Redis checkout lock
  -> checkout event mapper
  -> order creation service
  -> database write

After durable order creation
  -> Redis hot cache writes
  -> outbox_event row for Debezium publishing

Customer/store REST
  -> controllers
  -> service layer
  -> Redis repositories first
  -> database fallback where needed
```

## Current Package Structure

```text
com.sebet.order_service
  config/
  cache/
    config/
    dto/
    keys/
    repository/
    service/
  integration/
    checkout/
      consumer/
      event/
      mapper/
  order/
    command/
    service/
  persistence/
    entity/
    repository/
  customer/
    controller/
    dto/
  store/
    controller/
    dto/
  driver/
    controller/
    dto/
  internal/
    controller/
    dto/
    service/
  shared/
    enums/
    exception/
```

## Implemented Boundaries

- Controllers define endpoint contracts. Customer read endpoints, store read endpoints, store lifecycle write endpoints (accept, reject, ready, cancel, propose changes), driver detail/lifecycle endpoints (detail, pickup, arrive, complete, decline), internal driver assignment endpoints (assign, replace, unassign), and internal lifecycle endpoints for manual scheduled activation, system cancellation, and admin override cancellation are implemented; remaining customer writes and proposal cancellation still throw `UnsupportedOperationException`.
- `OrderCreationService` creates durable orders, order items, and initial status history from an internal command.
- Checkout integration DTOs model the cart-service checkout event.
- `CheckoutConfirmedEventConsumer` listens for raw checkout event JSON, deserializes the integration envelope, and delegates processing.
- `CheckoutConfirmedHandler` validates checkout event envelopes, performs processed-event idempotency, acquires `order:lock:{cartId}`, invokes order creation, and releases the lock.
- `CheckoutConfirmedEventMapper` translates validated checkout event envelopes into order creation commands.
- `OrderCreationService` and `OrderCreationRedisWriter` populate Redis hot views after the order transaction commits, using the current database order and history as the source of truth for replay safety.
- `OrderEventOutboxWriter` records order business events in `outbox_event` inside the same PostgreSQL transaction as the order state change.
- JPA entities and repositories own durable order persistence.
- Flyway owns the current PostgreSQL schema.
- Redis repositories own cache key usage and low-level Redis operations.
- `RedisKeys` centralizes all Redis key construction.
- Shared enums avoid duplicate lifecycle values across customer and store DTOs.
- Interceptors enforce identity headers by endpoint family.
- `GlobalExceptionHandler` maps common controller-handled exceptions to a consistent `ErrorResponse` JSON shape. Interceptor failures are sent directly with `sendError`.
- `DriverOrderController` and `DriverOrderLifecycleService` implement the driver detail and lifecycle path (detail, pickup, arrive, complete, decline).
- `DriverIdInterceptor` enforces the `X-Driver-Id` header on `/api/v1/driver/**`.
- `InternalOrderController` defines the internal service-to-service endpoint contracts. Driver assign/unassign calls are implemented through `InternalDriverAssignmentService`; manual scheduled activation, automated system cancellation, and admin override cancellation are implemented through `InternalOrderLifecycleService`; proposal-only cancellation and proposal-plus-order cancellation are still stubs.
- `InternalAuthInterceptor` enforces the `X-Internal-Key` header on `/api/v1/internal/**`.

## Planned Boundaries

- Customer/store services should own the remaining REST-facing workflows and business state transitions.
- Kafka consumers should deserialize external events, call mappers, and invoke services. Consumer startup should stay configuration-driven.
- Redis repositories should remain cache-specific and not contain lifecycle policy.
- WebSocket publisher components should emit live status/tracking updates after state changes.

## Interceptor Routing

| Path prefix | Interceptor | Required header |
|---|---|---|
| `/api/v1/orders/**` | `UserIdInterceptor` | `X-User-Id` |
| `/api/v1/store/**` | `StoreIdInterceptor` | `X-Store-Id` |
| `/api/v1/driver/**` | `DriverIdInterceptor` | `X-Driver-Id` |
| `/api/v1/internal/**` | `InternalAuthInterceptor` | `X-Internal-Key` |

## Key Design Rules

- Construct Redis keys only through `RedisKeys`.
- Keep customer and store DTOs separate.
- Keep domain enums in `shared/enums`.
- Treat Redis cache as hot read/write support, not the only durable store.
- Mark planned behavior clearly until implementation exists.
