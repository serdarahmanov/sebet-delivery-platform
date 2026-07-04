# ADR 0002: Use Redis for Hot Order Views

## Status

Proposed

## Context

Active order screens, store kitchen dashboards, tracking status, and verification code reads need fast access and short-lived state.

## Decision

Use Redis for hot order views and live state keys such as active order sets, order status, order snapshots, tracking data, timelines, proposals, and verification codes.

## Consequences

Positive:

- Fast reads for customer and store apps.
- Natural TTL support for temporary live state.
- Cache keys can be optimized by access pattern.

Negative:

- Requires careful cache lifecycle management.
- Requires durable PostgreSQL fallback for history and receipts.
- Requires cache/database consistency rules.
