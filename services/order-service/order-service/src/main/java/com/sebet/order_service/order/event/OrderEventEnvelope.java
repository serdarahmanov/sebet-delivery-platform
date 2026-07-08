package com.sebet.order_service.order.event;

public record OrderEventEnvelope<T>(
        String eventId,
        String eventType,
        String aggregateId,
        String aggregateType,
        long version,
        String occurredAt,
        String source,
        T data
) {
}
