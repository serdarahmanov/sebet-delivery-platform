package com.sebet.cartservice.cart.store.projection.event;

import java.time.Instant;

public record StoreEvent(
        String eventId,
        String eventType,
        String aggregateId,
        String aggregateType,
        Long version,
        Instant occurredAt,
        String source,
        StoreEventData data
) {
}
