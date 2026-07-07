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
- `DRIVER_ASSIGNED` removed from `OrderStatus` enum; driver assignment modelled as `driverId` / `driverAssignedAt` metadata fields on the order (added directly to V1 migration).
- `CustomerOrderQueryService` — all 10 customer-facing GET methods (history feed, active orders list, active order detail, scheduled detail, cancelled detail, smart router, status, tracking, verification code, proposed changes). Redis-first with DB fallback; ownership verification returns 404 for both not-found and wrong-user (security by obscurity).

Pending:

- store-facing service methods
- order status transition service methods (accept, reject, pickup, arrive, complete, cancel, etc.)
- proposals write path (respond-to-changes)
- cancellation write path (customer cancel)
- delivery completion behavior
- scheduled order update write path
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

Implemented (stubs — service layer pending):

- `GET  /{orderId}` — delivery detail
- `POST /{orderId}/pickup` — READY_FOR_PICKUP → OUT_FOR_DELIVERY
- `POST /{orderId}/arrive` — OUT_FOR_DELIVERY → ARRIVED; generates verification code → C7
- `POST /{orderId}/complete` — ARRIVED → DELIVERED; validates C7 code
- `POST /{orderId}/decline` — unassigns driver; valid before OUT_FOR_DELIVERY

## Internal Endpoints

Implemented (stubs — service layer pending):

- `POST /{orderId}/assign-driver` — sets driverId + driverAssignedAt (dispatch)
- `POST /{orderId}/unassign-driver` — clears driverId; valid on any non-terminal status
- `POST /{orderId}/system-cancel` — system-initiated cancellation
- `POST /{orderId}/activate-scheduled` — SCHEDULED → PENDING
- `POST /{orderId}/cancel-proposal` — AWAITING_CUSTOMER_RESPONSE → CANCELLED

## Error Handling

Implemented:

- `GlobalExceptionHandler` (`@RestControllerAdvice`) mapping common exceptions to consistent HTTP responses
- `ErrorResponse` record (`shared/exception/`) with `code`, `message`, and `timestamp` fields
- Input validation for amount fields (`>= 0`) in `CheckoutConfirmedEvent` and `CreateOrderCommand` compact constructors
- `deliveryAddressJson` JSON parse validation in `OrderCreationService` before DB write

Implemented:

- `ORDER_NOT_FOUND` (404) — raised by `OrderNotFoundException`, used for both not-found and wrong-owner responses.

Pending:

- `ORDER_NOT_CANCELLABLE`, `PROPOSAL_EXPIRED`, and other domain-specific codes as write paths are implemented
- `409 Conflict` handling once lifecycle transition rules are enforced

## Deployment

Pending:

- Dockerfile
- compose integration
- Kubernetes manifest or Helm chart
- production profile
- health probes
