package com.sebet.cartservice.cart.product.projection.event;

import java.time.Instant;

public record ProductEvent(
        String eventId,
        String eventType,
        String aggregateId,
        String aggregateType,
        Long version,
        Instant occurredAt,
        String source,
        ProductEventData data
) {
}
