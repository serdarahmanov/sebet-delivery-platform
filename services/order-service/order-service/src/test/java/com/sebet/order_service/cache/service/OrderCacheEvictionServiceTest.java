package com.sebet.order_service.cache.service;

import com.sebet.order_service.cache.eviction.C2CacheEvictionStrategy;
import com.sebet.order_service.cache.eviction.CancelledOrderHotViewsCacheEvictionStrategy;
import com.sebet.order_service.cache.eviction.ProposeChangesHotViewsEvictionStrategy;
import com.sebet.order_service.cache.keys.RedisKeys;
import com.sebet.order_service.order.event.OrderEventOutboxWriter;
import com.sebet.order_service.shared.exception.CacheInvalidationFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCacheEvictionServiceTest {

    private final C2CacheEvictionStrategy c2Strategy = mock(C2CacheEvictionStrategy.class);
    private final CancelledOrderHotViewsCacheEvictionStrategy cancelledOrderHotViewsStrategy =
            mock(CancelledOrderHotViewsCacheEvictionStrategy.class);
    private final OrderEventOutboxWriter orderEventOutboxWriter = mock(OrderEventOutboxWriter.class);
    private final OrderCacheEvictionService service = new OrderCacheEvictionService(
            c2Strategy,
            cancelledOrderHotViewsStrategy,
            orderEventOutboxWriter
    );

    @Test
    void evictC2OrRequestEviction_deletesDirectlyWhenRedisIsAvailable() {
        String orderId = UUID.randomUUID().toString();

        service.evictC2OrRequestEviction(orderId, "INTERNAL_ASSIGN_DRIVER", "idem-1");

        verify(c2Strategy).evict(orderId);
        verify(orderEventOutboxWriter, never()).saveOrderCacheEvictionRequested(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void evictC2OrRequestEviction_recordsEvictionEventWhenRedisFails() {
        String orderId = UUID.randomUUID().toString();
        doThrow(new RedisConnectionFailureException("redis unavailable"))
                .when(c2Strategy).evict(orderId);
        when(c2Strategy.cacheName()).thenReturn("C2");
        when(c2Strategy.cacheKey(orderId)).thenReturn(RedisKeys.order(orderId));
        when(c2Strategy.cacheKeys(orderId)).thenReturn(List.of(RedisKeys.order(orderId)));

        service.evictC2OrRequestEviction(orderId, "INTERNAL_ASSIGN_DRIVER", "idem-1");

        verify(orderEventOutboxWriter).saveOrderCacheEvictionRequested(
                eq(orderId),
                eq("C2"),
                eq(RedisKeys.order(orderId)),
                eq(List.of(RedisKeys.order(orderId))),
                eq("DRIVER_ASSIGNMENT_CHANGED"),
                eq("INTERNAL_ASSIGN_DRIVER"),
                eq("idem-1"),
                eq("RedisConnectionFailureException"),
                eq("redis unavailable"),
                any(OffsetDateTime.class)
        );
    }

    @Test
    void evictC2OrRequestEviction_recordsEvictionEventWhenRedisResultIsUnknown() {
        String orderId = UUID.randomUUID().toString();
        doThrow(new QueryTimeoutException("redis command timed out"))
                .when(c2Strategy).evict(orderId);
        when(c2Strategy.cacheName()).thenReturn("C2");
        when(c2Strategy.cacheKey(orderId)).thenReturn(RedisKeys.order(orderId));
        when(c2Strategy.cacheKeys(orderId)).thenReturn(List.of(RedisKeys.order(orderId)));

        service.evictC2OrRequestEviction(orderId, "INTERNAL_ASSIGN_DRIVER", "idem-1");

        verify(orderEventOutboxWriter).saveOrderCacheEvictionRequested(
                eq(orderId),
                eq("C2"),
                eq(RedisKeys.order(orderId)),
                eq(List.of(RedisKeys.order(orderId))),
                eq("DRIVER_ASSIGNMENT_CHANGED"),
                eq("INTERNAL_ASSIGN_DRIVER"),
                eq("idem-1"),
                eq("QueryTimeoutException"),
                eq("redis command timed out"),
                any(OffsetDateTime.class)
        );
    }

    @Test
    void evictC2OrRequestEviction_throwsWhenEvictionEventCannotBeRecorded() {
        String orderId = UUID.randomUUID().toString();
        doThrow(new RedisConnectionFailureException("redis unavailable"))
                .when(c2Strategy).evict(orderId);
        when(c2Strategy.cacheName()).thenReturn("C2");
        when(c2Strategy.cacheKey(orderId)).thenReturn(RedisKeys.order(orderId));
        when(c2Strategy.cacheKeys(orderId)).thenReturn(List.of(RedisKeys.order(orderId)));
        doThrow(new IllegalStateException("database unavailable"))
                .when(orderEventOutboxWriter)
                .saveOrderCacheEvictionRequested(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
                );

        assertThatThrownBy(() -> service.evictC2OrRequestEviction(orderId, "INTERNAL_ASSIGN_DRIVER", "idem-1"))
                .isInstanceOf(CacheInvalidationFailedException.class)
                .hasMessageContaining("cache invalidation failed");
    }

    @Test
    void evictC2OrRequestEviction_propagatesNonRedisRuntimeFailure() {
        String orderId = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("serialization failed"))
                .when(c2Strategy).evict(orderId);

        assertThatThrownBy(() -> service.evictC2OrRequestEviction(orderId, "INTERNAL_ASSIGN_DRIVER", "idem-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serialization failed");

        verify(orderEventOutboxWriter, never()).saveOrderCacheEvictionRequested(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void evictCancelledOrderHotViewsOrRequestEviction_recordsGroupedEvictionEventWhenRedisFails() {
        String orderId = UUID.randomUUID().toString();
        List<String> cacheKeys = List.of(
                RedisKeys.activeOrders("customer-1"),
                RedisKeys.storeActiveOrders("store-1"),
                RedisKeys.order(orderId),
                RedisKeys.orderTracking(orderId),
                RedisKeys.orderStatus(orderId),
                RedisKeys.orderTimeline(orderId)
        );
        doThrow(new RedisConnectionFailureException("redis unavailable"))
                .when(cancelledOrderHotViewsStrategy).evict(orderId);
        when(cancelledOrderHotViewsStrategy.cacheName()).thenReturn("CANCELLED_ORDER_HOT_VIEWS");
        when(cancelledOrderHotViewsStrategy.cacheKey(orderId)).thenReturn(RedisKeys.order(orderId));
        when(cancelledOrderHotViewsStrategy.cacheKeys(orderId)).thenReturn(cacheKeys);

        service.evictCancelledOrderHotViewsOrRequestEviction(orderId, "STORE_CANCEL_ORDER", "idem-1");

        verify(orderEventOutboxWriter).saveOrderCacheEvictionRequested(
                eq(orderId),
                eq("CANCELLED_ORDER_HOT_VIEWS"),
                eq(RedisKeys.order(orderId)),
                eq(cacheKeys),
                eq("ORDER_CANCELLED"),
                eq("STORE_CANCEL_ORDER"),
                eq("idem-1"),
                eq("RedisConnectionFailureException"),
                eq("redis unavailable"),
                any(OffsetDateTime.class)
        );
    }

    @Test
    void requestEvictionAfterUpdateFailure_recordsEvictionEventForRecoverableRedisFailure() {
        String orderId = UUID.randomUUID().toString();
        ProposeChangesHotViewsEvictionStrategy strategy = mock(ProposeChangesHotViewsEvictionStrategy.class);
        when(strategy.cacheName()).thenReturn("PROPOSE_CHANGES_HOT_VIEWS");
        when(strategy.cacheKey(orderId)).thenReturn(RedisKeys.orderProposals(orderId));
        when(strategy.cacheKeys(orderId)).thenReturn(List.of(
                RedisKeys.orderProposals(orderId),
                RedisKeys.orderStatus(orderId),
                RedisKeys.orderTimeline(orderId)
        ));
        RuntimeException updateFailure = new RedisConnectionFailureException("redis down");

        service.requestEvictionAfterUpdateFailure(
                orderId, strategy, "PROPOSE_CHANGES", "STORE_PROPOSE_CHANGES", "idem-1", updateFailure);

        verify(orderEventOutboxWriter).saveOrderCacheEvictionRequested(
                eq(orderId),
                eq("PROPOSE_CHANGES_HOT_VIEWS"),
                eq(RedisKeys.orderProposals(orderId)),
                eq(List.of(
                        RedisKeys.orderProposals(orderId),
                        RedisKeys.orderStatus(orderId),
                        RedisKeys.orderTimeline(orderId)
                )),
                eq("PROPOSE_CHANGES"),
                eq("STORE_PROPOSE_CHANGES"),
                eq("idem-1"),
                eq("RedisConnectionFailureException"),
                eq("redis down"),
                any(OffsetDateTime.class)
        );
    }

    @Test
    void requestEvictionAfterUpdateFailure_propagatesNonRedisRuntimeFailure() {
        String orderId = UUID.randomUUID().toString();
        RuntimeException nonRedisFailure = new IllegalStateException("serialization failed");

        assertThatThrownBy(() -> service.requestEvictionAfterUpdateFailure(
                orderId, c2Strategy, "REASON", "ACTION", "idem-1", nonRedisFailure))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serialization failed");

        verify(orderEventOutboxWriter, never()).saveOrderCacheEvictionRequested(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void requestEvictionAfterUpdateFailure_throwsWhenEvictionEventCannotBeRecorded() {
        String orderId = UUID.randomUUID().toString();
        when(c2Strategy.cacheName()).thenReturn("C2");
        when(c2Strategy.cacheKey(orderId)).thenReturn(RedisKeys.order(orderId));
        when(c2Strategy.cacheKeys(orderId)).thenReturn(List.of(RedisKeys.order(orderId)));
        doThrow(new IllegalStateException("database unavailable"))
                .when(orderEventOutboxWriter)
                .saveOrderCacheEvictionRequested(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
                );
        RuntimeException updateFailure = new RedisConnectionFailureException("redis down");

        assertThatThrownBy(() -> service.requestEvictionAfterUpdateFailure(
                orderId, c2Strategy, "REASON", "STORE_PROPOSE_CHANGES", "idem-1", updateFailure))
                .isInstanceOf(CacheInvalidationFailedException.class)
                .hasMessageContaining("cache invalidation failed");
    }

    @Test
    void evictOrRequestEviction_usesProvidedStrategyForGenericCache() {
        String orderId = UUID.randomUUID().toString();
        String fakeCacheKey = "tracking:" + orderId;
        C2CacheEvictionStrategy otherStrategy = mock(C2CacheEvictionStrategy.class);
        doThrow(new RedisConnectionFailureException("redis down")).when(otherStrategy).evict(orderId);
        when(otherStrategy.cacheName()).thenReturn("C3");
        when(otherStrategy.cacheKey(orderId)).thenReturn(fakeCacheKey);
        when(otherStrategy.cacheKeys(orderId)).thenReturn(List.of(fakeCacheKey));

        service.evictOrRequestEviction(orderId, otherStrategy, "STATUS_CHANGED", "STORE_ACCEPT", "idem-2");

        verify(orderEventOutboxWriter).saveOrderCacheEvictionRequested(
                eq(orderId),
                eq("C3"),
                eq(fakeCacheKey),
                eq(List.of(fakeCacheKey)),
                eq("STATUS_CHANGED"),
                eq("STORE_ACCEPT"),
                eq("idem-2"),
                any(),
                any(),
                any(OffsetDateTime.class)
        );
    }
}
