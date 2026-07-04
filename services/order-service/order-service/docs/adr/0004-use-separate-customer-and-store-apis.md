# ADR 0004: Use Separate Customer and Store APIs

## Status

Accepted

## Context

Customers and stores see different order views and perform different actions. Sharing one controller or response model would mix concerns and leak fields across clients.

## Decision

Use separate customer and store controllers, DTOs, and route families.

## Consequences

Positive:

- Clear client-specific contracts.
- Easier frontend integration.
- Less risk of exposing store-only or customer-only fields incorrectly.

Negative:

- Some response fragments need shared DTOs.
- Lifecycle changes may require updates in both API families.
