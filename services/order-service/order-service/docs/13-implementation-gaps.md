# Implementation Gaps

This file tracks behavior that is designed in the docs and DTOs but not implemented in code yet.

## Service Layer

All controller methods currently throw:

```text
UnsupportedOperationException("Not implemented yet")
```

Implemented:

- `OrderCreationService` creates durable orders from internal checkout commands.

Pending:

- customer-facing service methods
- store-facing service methods
- order status transition service methods
- proposals, tracking, verification, cancellation, and delivery completion behavior

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

Pending:

- global exception handler
- standard error DTO
- stable error codes

## Deployment

Pending:

- Dockerfile
- compose integration
- Kubernetes manifest or Helm chart
- production profile
- health probes
