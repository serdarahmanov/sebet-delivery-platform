# ADR 0001: Create Orders From Checkout Events

## Status

Accepted and implemented for checkout confirmation consumption

## Context

Cart-service owns cart checkout confirmation and emits a checkout event after removing the basket. Order-service should not create orders directly from a public REST endpoint because order creation must follow successful cart confirmation.

## Decision

Create orders by consuming `CheckoutConfirmedEvent` from Kafka.

The checkout consumer, event DTOs, mapper, order creation service integration, retry/DLT handling, and Redis checkout lock are implemented. Order-created/status event publishing is still pending.

## Consequences

Positive:

- Keeps cart and order ownership separate.
- Preserves event-driven handoff between services.
- Allows order-service to own all post-checkout lifecycle behavior.

Negative:

- Requires idempotent event handling.
- Kafka retry/DLT handling must stay configured for failed checkout event processing.
- Order creation is eventually consistent after checkout confirmation.
