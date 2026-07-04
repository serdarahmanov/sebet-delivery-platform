# ADR 0005: Use HTTP Clients for Promotion and Delivery Decisions

## Status

Accepted

## Context

Promotion and delivery pricing decisions belong to separate services. Cart-service needs current promotion evaluation and delivery quote information during cart and checkout flows.

## Decision

Call promotion-service and delivery-service over HTTP using `WebClient`, short timeouts, metrics, and Resilience4j circuit breakers.

## Consequences

Positive:

- Keeps domain ownership clear.
- Allows cart-service to use current promotion and delivery decisions.
- Circuit breakers and timeouts protect cart latency.

Negative:

- Cart behavior depends partly on downstream availability.
- Degraded behavior must be carefully surfaced to users.
- Checkout must treat missing delivery quote information as blocking.
