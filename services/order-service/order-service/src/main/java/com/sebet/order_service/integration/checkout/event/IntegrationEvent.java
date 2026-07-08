package com.sebet.order_service.integration.checkout.event;

import java.time.Instant;
import java.util.UUID;

public record IntegrationEvent<T>(
        UUID eventId,
        String eventType,
        Integer eventVersion,
        String aggregateType,
        String aggregateId,
        Instant occurredAt,
        String source,
        T data
) {
}
