# Implementation Gaps

This file tracks behavior that is designed in the docs and DTOs but not implemented in code yet.

## Service Layer

All controller methods currently throw:

```text
UnsupportedOperationException("Not implemented yet")
```

Implemented:

- `OrderCreationService` creates durable orders from internal checkout commands.
- Redis hot-view initialization for created orders from current database state.
- `DRIVER_ASSIGNED` removed from `OrderStatus` enum; driver assignment modelled as `driverId` / `driverAssignedAt` metadata fields on the order (V2 migration).

Pending:

- customer-facing service methods
- store-facing service methods
- order status transition service methods
- proposals, tracking, verification, cancellation, and delivery completion behavior
- hot-view repair for non-checkout write paths

## Database

Implemented:

- JPA entities for `orders`, `order_items`, and `order_status_history`
- JPA repositories
- Flyway migration `V1__create_order_tables.sql`
- unique `cart_id` idempotency constraint
- repository tests

Pending:

- durable proposal/refund/verification fields if required by later workflows
- further indexes based on actual query patterns

## Kafka

Implemented:

- checkout confirmed event DTOs
- checkout event to order creation command mapper
- checkout event consumer
- real-broker Kafka listener integration tests
- checkout event retry and DLT handling
- checkout DLT topic startup validation
- Kafka retry/DLT integration coverage for retryable, non-retryable, malformed payload, partition/key preservation, and DLT publish failure paths
- Redis lock integration for checkout event handling
- Redis hot-view initialization for created orders from current database state

Pending:

- delivery arrival consumer
- order event publisher

## Background Jobs

Pending:

- scheduled order transition job
- proposal timeout job
- store response timeout job if needed

## WebSocket

Pending:

- STOMP broker configuration
- SockJS fallback if required
- topic naming
- customer/store push payloads
- authorization rules for subscriptions

## Driver Endpoints

Pending:

- delivery verification endpoint
- driver tracking update endpoint if order-service owns live tracking writes

## Error Handling

Implemented:

- `GlobalExceptionHandler` (`@RestControllerAdvice`) mapping common exceptions to consistent HTTP responses
- `ErrorResponse` record (`shared/exception/`) with `code`, `message`, and `timestamp` fields
- Input validation for amount fields (`>= 0`) in `CheckoutConfirmedEvent` and `CreateOrderCommand` compact constructors
- `deliveryAddressJson` JSON parse validation in `OrderCreationService` before DB write

Pending:

- domain-specific error codes (`ORDER_NOT_FOUND`, `ORDER_NOT_CANCELLABLE`, etc.) as service layer is implemented
- `403 Forbidden` and `409 Conflict` handling once lifecycle transition rules are enforced

## Deployment

Pending:

- Dockerfile
- compose integration
- Kubernetes manifest or Helm chart
- production profile
- health probes
