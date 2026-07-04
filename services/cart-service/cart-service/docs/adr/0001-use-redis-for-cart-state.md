# ADR 0001: Use Redis for Active Cart State

## Status

Accepted

## Context

Carts are user-specific, frequently updated, and need low-latency reads and writes. The service also needs TTL-based cleanup for abandoned carts.

## Decision

Use Redis as the primary storage for active cart state.

## Consequences

Positive:

- Fast reads and writes.
- Natural TTL support.
- Simple key-based access by user id.
- Good fit for transient cart data.

Negative:

- Requires explicit schema migration strategy.
- Requires optimistic concurrency handling.
- Redis data shape must be managed carefully during rolling deployments.
