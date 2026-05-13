package com.sebet.cartservice.cart.inventory.projection.event;

import java.time.Instant;

public record InventoryEvent(
        String eventId,
        String eventType,
        String aggregateId,
        String aggregateType,
        Long version,
        Instant occurredAt,
        String source,
        InventoryEventData data
) {
}
