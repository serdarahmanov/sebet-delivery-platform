# ADR 0006: Remove Basket Before Checkout Event Publish

## Status

Accepted

## Context

Checkout confirmation removes a basket from the cart and publishes a `CheckoutConfirmed` event. If the event is published before Redis persistence, a CAS conflict could leave the basket in Redis while downstream services process an order.

## Decision

Persist basket removal through Redis CAS before publishing `CheckoutConfirmed`.

## Consequences

Positive:

- Prevents publishing an event for a basket that failed to persist as removed.
- Avoids duplicate order risk on client retry after CAS conflict.

Negative:

- If Redis CAS succeeds but Kafka publish fails, the basket is already removed.
- The client receives 503 and the user may need to rebuild the basket.
- An outbox pattern would be safer long term.
