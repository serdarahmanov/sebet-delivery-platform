# ADR 0003: Centralize Redis Key Construction

## Status

Accepted

## Context

Order-service uses multiple Redis keys for active orders, scheduled orders, snapshots, status, locks, timeline, verification, and proposals. Duplicated string construction would make key changes risky.

## Decision

Centralize all Redis key construction in `RedisKeys`.

## Consequences

Positive:

- Key formats are easy to audit.
- Repository code avoids duplicate string literals.
- Future key migrations are easier to coordinate.

Negative:

- All new cache keys must be added deliberately.
- `RedisKeys` must remain stable and reviewed carefully.
