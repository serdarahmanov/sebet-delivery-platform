# Architecture

## High-Level Shape

Order-service is designed as an event-entry microservice.

Orders are not created directly by REST. The planned entry point is a Kafka `CheckoutConfirmedEvent` from cart-service. After creation, customer and store clients interact with the order through REST APIs and, later, WebSocket/STOMP live updates.

```text
Kafka checkout event
  -> checkout event mapper
  -> order creation service
  -> database write
  -> Redis hot cache writes
  -> order event publishing

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
  integration/
    checkout/
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
  shared/
    enums/
```

## Implemented Boundaries

- Controllers define endpoint contracts but currently throw `UnsupportedOperationException`.
- `OrderCreationService` creates durable orders, order items, and initial status history from an internal command.
- Checkout integration DTOs model the planned cart-service checkout event.
- `CheckoutConfirmedEventMapper` translates checkout events into order creation commands.
- JPA entities and repositories own durable order persistence.
- Flyway owns the current PostgreSQL schema.
- Redis repositories own cache key usage and low-level Redis operations.
- `RedisKeys` centralizes all Redis key construction.
- Shared enums avoid duplicate lifecycle values across customer and store DTOs.
- Interceptors enforce identity headers by endpoint family.

## Planned Boundaries

- Customer/store services should own REST-facing workflows and business state transitions.
- Kafka consumers should deserialize external events, call mappers, and invoke services.
- Redis repositories should remain cache-specific and not contain lifecycle policy.
- WebSocket publisher components should emit live status/tracking updates after state changes.

## Interceptor Routing

| Path prefix | Interceptor | Required header |
|---|---|---|
| `/api/v1/orders/**` | `UserIdInterceptor` | `X-User-Id` |
| `/api/v1/store/**` | `StoreIdInterceptor` | `X-Store-Id` |

## Key Design Rules

- Construct Redis keys only through `RedisKeys`.
- Keep customer and store DTOs separate.
- Keep domain enums in `shared/enums`.
- Treat Redis cache as hot read/write support, not the only durable store.
- Mark planned behavior clearly until implementation exists.
