package com.sebet.order_service.cache.repository;

import com.sebet.order_service.cache.keys.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.Set;

/**
 * Cache 1b — store:active_orders:{storeId}  (Redis SET)
 *
 * Tracks all currently active order IDs belonging to a store.
 * Maintained in parallel with Cache 1 ({@link ActiveOrdersRedisRepository})
 * so the store-facing controller can list its live orders without a DB query.
 *
 * Lifecycle rules:
 *  - Key is created on the store's first active order (SADD auto-creates it).
 *  - A single orderId is removed when its order reaches a terminal state
 *    (DELIVERED, CANCELLED) — must be called alongside the user-side remove.
 *  - The entire key is deleted atomically when the set becomes empty.
 *  - No TTL — managed entirely through explicit add/remove calls.
 *
 * Statuses tracked: PENDING, CONFIRMED, READY_FOR_PICKUP, OUT_FOR_DELIVERY, ARRIVED.
 * DELIVERED and CANCELLED are terminal — orders are removed at that point.
 */
@Repository
@RequiredArgsConstructor
public class StoreActiveOrdersRedisRepository {

    private final StringRedisTemplate redisTemplate;

    /**
     * Reuses the same atomic Lua script as {@link ActiveOrdersRedisRepository}:
     * SREM the orderId, then DEL the key if the set is now empty.
     * Spring resolves the correct bean by field name.
     */
    private final RedisScript<Long> removeActiveOrderScript;

    /**
     * Adds an orderId to the store's active-order set.
     * Creates the key if it does not yet exist (Redis SADD semantics).
     */
    public void add(String storeId, String orderId) {
        redisTemplate.opsForSet().add(RedisKeys.storeActiveOrders(storeId), orderId);
    }

    /**
     * Removes an orderId from the store's active-order set and deletes the
     * entire key atomically if the set is now empty.
     *
     * Uses the shared Lua script to close the race where a concurrent SADD
     * could land between a plain SREM and a subsequent DEL.
     */
    public void remove(String storeId, String orderId) {
        redisTemplate.execute(
                removeActiveOrderScript,
                Collections.singletonList(RedisKeys.storeActiveOrders(storeId)),
                orderId
        );
    }

    /**
     * Returns all active order IDs for the store.
     * Returns an empty set (never null) when the key does not exist.
     */
    public Set<String> getAll(String storeId) {
        Set<String> result = redisTemplate.opsForSet().members(RedisKeys.storeActiveOrders(storeId));
        return result != null ? result : Collections.emptySet();
    }

    /**
     * Returns {@code true} if the given orderId is currently active for the store.
     * Backed by Redis SISMEMBER — O(1).
     */
    public boolean contains(String storeId, String orderId) {
        Boolean member = redisTemplate.opsForSet().isMember(RedisKeys.storeActiveOrders(storeId), orderId);
        return Boolean.TRUE.equals(member);
    }

    /**
     * Returns the number of active orders the store currently has.
     * Returns 0 when the key does not exist.
     */
    public long count(String storeId) {
        Long size = redisTemplate.opsForSet().size(RedisKeys.storeActiveOrders(storeId));
        return size != null ? size : 0L;
    }
}
