# ADR 0004: Use Kafka for Projection Updates

## Status

Accepted

## Context

Cart-service needs product, inventory, and store updates without synchronously calling source services during cart validation.

## Decision

Consume Kafka events from product, inventory, and store topics and update local projection tables.

## Consequences

Positive:

- Decouples cart reads from source-service availability.
- Supports eventual consistency.
- Enables retry and DLT handling for failed events.

Negative:

- Requires monitoring for consumer lag and DLT growth.
- Projection staleness must be considered in checkout behavior.
- Event contract changes must be coordinated.
