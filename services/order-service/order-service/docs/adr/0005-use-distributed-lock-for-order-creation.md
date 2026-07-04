# ADR 0005: Use Distributed Lock for Order Creation

## Status

Proposed

## Context

Kafka can redeliver messages, and multiple service instances can process the same checkout event. Order creation must avoid duplicate orders for the same cart.

## Decision

Use `order:lock:{cartId}` with Redis `SET NX EX` during order creation, and release it with a compare-and-delete Lua script.

## Consequences

Positive:

- Prevents concurrent duplicate creation attempts.
- Lock self-expires if an instance crashes.
- Lua release avoids deleting another instance's lock.

Negative:

- Locking is not a replacement for durable idempotency.
- Database-level uniqueness should still protect against duplicates.
- Lock TTL must be chosen carefully.
