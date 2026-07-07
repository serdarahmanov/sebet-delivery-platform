# Implementation Gaps

This file tracks behavior that is designed in the docs and DTOs but not implemented in code yet.

## Service Layer

Many controller methods still throw:

```text
UnsupportedOperationException("Not implemented yet")
```

Implemented:

- `OrderCreationService` creates durable orders from internal checkout commands.
- Redis hot-view initialization for created orders from current database state.
- `DRIVER_ASSIGNED` removed from `OrderStatus` enum; driver assignment is modelled as `driverId` / `driverAssignedAt` metadata fields on the order.
- `CustomerOrderQueryService` implements all 10 customer-facing GET methods: history feed, active orders list, active order detail, scheduled detail, cancelled detail, smart router, status, tracking, verification code, and proposed changes.
- Customer read ownership verification returns 404 for both not-found and wrong-user responses.
- `StoreOrderQueryService` implements all 5 store-facing GET methods: history feed, active orders list, scheduled orders list, order detail, and status.
- Store read ownership verification returns 404 for both not-found and wrong-store responses.
- `OrderLifecycleService` implements the first store lifecycle transitions:
  - `PENDING -> CONFIRMED` through `POST /api/v1/store/orders/{orderId}/accept`
  - `PENDING -> CANCELLED` through `POST /api/v1/store/orders/{orderId}/reject`
  - `CONFIRMED -> READY_FOR_PICKUP` through `POST /api/v1/store/orders/{orderId}/ready`
- Store lifecycle writes use `orders.version` optimistic locking so concurrent lifecycle updates cannot both commit.
- Store `OUT_OF_STOCK` rejection validation verifies that item details are present, belong to the order, match requested quantity/unit, and provide a valid partial-stock quantity when applicable.
- Store `OUT_OF_STOCK` rejection validation rejects duplicate product ids and null list elements.
- Store rejection metadata is persisted into `order_status_history.metadata_json`.
- Redis lifecycle transition updates for C4 status, C6 `PACKED` timeline append, and terminal cancellation hot-view cleanup.

Pending:

- remaining store write methods: `cancel`, `propose-changes`
- remaining lifecycle transitions: customer cancel, scheduled activation, proposal resolution, driver pickup, driver arrive, driver complete, and delivery cancellation paths
- proposals write path (`respond-to-changes`)
- scheduled order update write path
- hot-view repair for non-checkout write paths beyond the first store transitions

## Database

Implemented:

- JPA entities for `orders`, `order_items`, and `order_status_history`
- JPA repositories
- Flyway migration `V1__create_order_tables.sql`
- unique `cart_id` idempotency constraint
- optimistic lock `orders.version` column
- unique `(order_id, product_id)` order item constraint
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

Implemented contracts only; service layer pending:

- `GET  /{orderId}` - delivery detail
- `POST /{orderId}/pickup` - `READY_FOR_PICKUP -> OUT_FOR_DELIVERY`
- `POST /{orderId}/arrive` - `OUT_FOR_DELIVERY -> ARRIVED`; generates verification code into C7
- `POST /{orderId}/complete` - `ARRIVED -> DELIVERED`; validates C7 code
- `POST /{orderId}/decline` - unassigns driver; valid before `OUT_FOR_DELIVERY`

## Internal Endpoints

Implemented contracts only; service layer pending:

- `POST /{orderId}/assign-driver` - sets `driverId` and `driverAssignedAt`
- `POST /{orderId}/unassign-driver` - clears `driverId`; valid on any non-terminal status
- `POST /{orderId}/system-cancel` - system-initiated cancellation
- `POST /{orderId}/activate-scheduled` - `SCHEDULED -> PENDING`
- `POST /{orderId}/cancel-proposal` - `AWAITING_CUSTOMER_RESPONSE -> CANCELLED`

## Error Handling

Implemented:

- `GlobalExceptionHandler` (`@RestControllerAdvice`) maps common exceptions to consistent HTTP responses.
- `ErrorResponse` record (`shared/exception/`) with `code`, `message`, and `timestamp` fields.
- Input validation for amount fields (`>= 0`) in `CheckoutConfirmedEvent` and `CreateOrderCommand` compact constructors.
- `deliveryAddressJson` JSON parse validation in `OrderCreationService` before DB write.
- `ORDER_NOT_FOUND` (404), raised by `OrderNotFoundException`, is used for both not-found and wrong-owner responses.
- `ORDER_INVALID_TRANSITION` (409), raised by `OrderInvalidTransitionException`, is used for invalid lifecycle transitions.
- `OptimisticLockingFailureException` is mapped to `ORDER_INVALID_TRANSITION` (409) for stale concurrent lifecycle writes.

Pending:

- `ORDER_NOT_CANCELLABLE`, `PROPOSAL_EXPIRED`, and other domain-specific codes as write paths are implemented

## Deployment

Pending:

- Dockerfile
- compose integration
- Kubernetes manifest or Helm chart
- production profile
- health probes
