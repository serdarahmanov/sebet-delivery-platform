# ADR 0001: Create Orders From Checkout Events

## Status

Proposed

## Context

Cart-service owns cart checkout confirmation and emits a checkout event after removing the basket. Order-service should not create orders directly from a public REST endpoint because order creation must follow successful cart confirmation.

## Decision

Create orders by consuming `CheckoutConfirmedEvent` from Kafka.

## Consequences

Positive:

- Keeps cart and order ownership separate.
- Preserves event-driven handoff between services.
- Allows order-service to own all post-checkout lifecycle behavior.

Negative:

- Requires idempotent event handling.
- Requires Kafka retry/DLT strategy.
- Order creation is eventually consistent after checkout confirmation.
