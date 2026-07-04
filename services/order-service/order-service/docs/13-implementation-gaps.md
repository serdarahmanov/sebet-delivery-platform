# Implementation Gaps

This file tracks behavior that is designed in the docs and DTOs but not implemented in code yet.

## Service Layer

All controller methods currently throw:

```text
UnsupportedOperationException("Not implemented yet")
```

Service classes need to be added for customer, store, order lifecycle, proposals, tracking, and verification behavior.

## Database

Pending:

- JPA entities
- JPA repositories
- Flyway migrations
- order status history table

## Kafka

Pending:

- checkout event consumer
- delivery arrival consumer
- order event publisher
- retry and DLT configuration
- idempotency checks for duplicate checkout events

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
