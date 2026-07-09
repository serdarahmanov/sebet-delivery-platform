package com.sebet.order_service.cache.service;

import com.sebet.order_service.cache.eviction.C2CacheEvictionStrategy;
import com.sebet.order_service.cache.eviction.CacheEvictionStrategy;
import com.sebet.order_service.order.event.OrderEventOutboxWriter;
import com.sebet.order_service.shared.exception.CacheInvalidationFailedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCacheEvictionService {

    private static final String DRIVER_ASSIGNMENT_CHANGED = "DRIVER_ASSIGNMENT_CHANGED";

    private final C2CacheEvictionStrategy c2Strategy;
    private final OrderEventOutboxWriter orderEventOutboxWriter;

    public void evictC2OrRequestEviction(String orderId, String sourceAction, String idempotencyKey) {
        evictOrRequestEviction(orderId, c2Strategy, DRIVER_ASSIGNMENT_CHANGED, sourceAction, idempotencyKey);
    }

    public void evictOrRequestEviction(
            String orderId,
            CacheEvictionStrategy strategy,
            String reason,
            String sourceAction,
            String idempotencyKey
    ) {
        try {
            strategy.evict(orderId);
            return;
        } catch (RuntimeException evictionException) {
            if (!isRecoverableRedisFailure(evictionException)) {
                throw evictionException;
            }
            log.warn("Failed to evict {} Redis snapshot directly; requesting async eviction orderId={} sourceAction={}",
                    strategy.cacheName(),
                    orderId,
                    sourceAction,
                    evictionException);
            requestAsyncEviction(orderId, strategy, reason, sourceAction, idempotencyKey, evictionException);
        }
    }

    private void requestAsyncEviction(
            String orderId,
            CacheEvictionStrategy strategy,
            String reason,
            String sourceAction,
            String idempotencyKey,
            RuntimeException evictionException
    ) {
        try {
            orderEventOutboxWriter.saveOrderCacheEvictionRequested(
                    orderId,
                    strategy.cacheName(),
                    strategy.cacheKey(orderId),
                    reason,
                    sourceAction,
                    idempotencyKey,
                    evictionException.getClass().getSimpleName(),
                    evictionException.getMessage(),
                    OffsetDateTime.now()
            );
        } catch (RuntimeException outboxException) {
            log.error("Failed to record {} Redis eviction request after direct eviction failed orderId={} sourceAction={}",
                    strategy.cacheName(),
                    orderId,
                    sourceAction,
                    outboxException);
            throw new CacheInvalidationFailedException(orderId, outboxException);
        }
    }

    private boolean isRecoverableRedisFailure(RuntimeException exception) {
        return exception instanceof RedisConnectionFailureException
                || exception instanceof QueryTimeoutException;
    }
}
