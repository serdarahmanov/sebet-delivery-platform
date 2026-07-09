package com.sebet.order_service.order.event;

import java.util.List;

public record OrderCacheEvictionRequestedEventData(
        String orderId,
        String cacheName,
        String cacheKey,
        List<String> cacheKeys,
        String reason,
        String sourceAction,
        String idempotencyKey,
        String failureType,
        String failureMessage,
        String requestedAt
) {
}
