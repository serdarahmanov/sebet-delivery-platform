package com.sebet.order_service.order.event;

public record OrderCacheEvictionRequestedEventData(
        String orderId,
        String cacheName,
        String cacheKey,
        String reason,
        String sourceAction,
        String idempotencyKey,
        String failureType,
        String failureMessage,
        String requestedAt
) {
}
