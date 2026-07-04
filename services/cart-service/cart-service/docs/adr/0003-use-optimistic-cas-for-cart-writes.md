# ADR 0003: Use Optimistic CAS for Cart Writes

## Status

Accepted

## Context

Multiple client requests can mutate the same user cart concurrently. The service needs to prevent lost updates without holding distributed locks around cart operations.

## Decision

Use Redis compare-and-swap with a Lua script and a separate version key.

## Consequences

Positive:

- Atomic write protection.
- No distributed lock lifecycle.
- Clear HTTP 409 behavior for concurrent modification.
- Cart JSON and version key are updated together.

Negative:

- Callers must record the expected version before mutation.
- Failed CAS writes require client retry behavior.
- Save methods mutate the in-memory cart version before serialization.
