package com.sebet.order_service.cache.repository;

import com.sebet.order_service.cache.keys.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;

/**
 * Cache 1c — store:scheduled_orders:{storeId}  (Redis ZSET)
 *
 * Tracks all scheduled order IDs for a store, sorted by their requested
 * delivery time (Unix epoch millis as the score).
 *
 * Using a Sorted Set (instead of a plain SET like Cache 1b) gives two benefits:
 *   1. The store's scheduled-orders list arrives pre-sorted by delivery time
 *      with no application-side sorting needed.
 *   2. The transition job can find "orders maturing in the next 30 minutes"
 *      with a single ZRANGEBYSCORE call instead of loading all IDs and filtering.
 *
 * Lifecycle rules:
 *   - Added when an order with {@code scheduleType == SCHEDULED} is created.
 *   - Removed when the order is transitioned to PENDING (30 min before window)
 *     and added to Cache 1b (store active orders).
 *   - Removed if the scheduled order is cancelled before it becomes active.
 *   - TTL is 48 hours, refreshed on add, aligned with order snapshot/status keys.
 *     Store read paths still fall back to PostgreSQL if membership and
 *     per-order cache keys drift.
 */
@Repository
@RequiredArgsConstructor
public class StoreScheduledOrdersRedisRepository {

    static final Duration TTL = Duration.ofHours(48);

    private final StringRedisTemplate redisTemplate;

    /**
     * Adds a scheduled order to the store's sorted set.
     * Score is the {@code scheduledFor} epoch-millis so the set stays
     * sorted by delivery time automatically.
     *
     * @param storeId     the store that will fulfil the order
     * @param orderId     the scheduled order ID
     * @param scheduledFor the requested delivery time
     */
    public void add(String storeId, String orderId, Instant scheduledFor) {
        String key = RedisKeys.storeScheduledOrders(storeId);
        redisTemplate.opsForZSet().add(
                key,
                orderId,
                scheduledFor.toEpochMilli()
        );
        redisTemplate.expire(key, TTL);
    }

    /**
     * Removes a single order from the store's scheduled set.
     * Called when the order transitions to the active queue or is cancelled.
     */
    public void remove(String storeId, String orderId) {
        redisTemplate.opsForZSet().remove(RedisKeys.storeScheduledOrders(storeId), orderId);
    }

    /**
     * Returns all scheduled order IDs for the store, sorted ascending by
     * delivery time (soonest first).
     *
     * Returns an empty set (never null) when no scheduled orders exist.
     */
    public Set<String> getAll(String storeId) {
        Set<String> result = redisTemplate.opsForZSet()
                .range(RedisKeys.storeScheduledOrders(storeId), 0, -1);
        return result != null ? result : Collections.emptySet();
    }

    /**
     * Returns order IDs whose {@code scheduledFor} time falls within
     * [{@code from}, {@code to}] (both inclusive, epoch-millis comparison).
     *
     * Primary use: the transition job queries
     * {@code getMaturing(storeId, now, now + 30 min)} every minute to find
     * orders that should enter the active queue.
     *
     * @param from start of the time window (inclusive)
     * @param to   end of the time window (inclusive)
     */
    public Set<String> getMaturing(String storeId, Instant from, Instant to) {
        Set<String> result = redisTemplate.opsForZSet().rangeByScore(
                RedisKeys.storeScheduledOrders(storeId),
                from.toEpochMilli(),
                to.toEpochMilli()
        );
        return result != null ? result : Collections.emptySet();
    }

    /**
     * Returns order IDs with their scheduled times as {@link ZSetOperations.TypedTuple}
     * pairs — useful when the caller needs both the ID and the delivery timestamp
     * without a secondary cache lookup.
     *
     * Results are sorted ascending by delivery time.
     */
    public Set<ZSetOperations.TypedTuple<String>> getAllWithScores(String storeId) {
        Set<ZSetOperations.TypedTuple<String>> result = redisTemplate.opsForZSet()
                .rangeWithScores(RedisKeys.storeScheduledOrders(storeId), 0, -1);
        return result != null ? result : Collections.emptySet();
    }

    /**
     * Returns {@code true} if the given orderId exists in the store's scheduled set.
     * Backed by Redis ZSCORE — O(log N).
     */
    public boolean contains(String storeId, String orderId) {
        Double score = redisTemplate.opsForZSet()
                .score(RedisKeys.storeScheduledOrders(storeId), orderId);
        return score != null;
    }

    /**
     * Returns the number of scheduled orders for the store.
     * Returns 0 when the key does not exist.
     */
    public long count(String storeId) {
        Long size = redisTemplate.opsForZSet().size(RedisKeys.storeScheduledOrders(storeId));
        return size != null ? size : 0L;
    }
}
