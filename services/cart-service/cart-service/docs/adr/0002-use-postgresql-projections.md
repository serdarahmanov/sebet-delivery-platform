# ADR 0002: Use PostgreSQL Projections for Validation Data

## Status

Accepted

## Context

Cart validation needs product, inventory, and store data, but cart-service should not own those domains. Calling multiple source services on every cart request would increase latency and reduce availability.

## Decision

Maintain local PostgreSQL projection tables for product, inventory, and store data. Update them from Kafka events.

## Consequences

Positive:

- Fast local validation queries.
- Cart-service can continue reading recent data even if source services are temporarily unavailable.
- Query timeouts and indexes can be tuned for cart validation.

Negative:

- Projection data can be stale.
- Event handling and DLT operations are required.
- Schema must stay aligned with incoming event contracts.
