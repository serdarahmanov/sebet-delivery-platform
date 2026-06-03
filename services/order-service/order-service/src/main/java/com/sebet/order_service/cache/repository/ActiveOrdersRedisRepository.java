package com.sebet.order_service.cache.repository;

import com.sebet.order_service.cache.keys.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.Set;

/**
 * Cache 1 — user:active_orders:{userId}  (Redis SET)
 *
 * Tracks all currently active order IDs for a user.
 * Used by:
 *  - the home screen to list ongoing orders
 *  - order creation to validate whether a new order can be placed
 *
 * Lifecycle rules:
 *  - Key is created on the user's first active order (SADD auto-creates it).
 *  - A single orderId is removed when its order completes or is cancelled.
 *  - The entire key is deleted immediately when the set becomes empty —
 *    no key should exist for a user who has no active orders.
 *  - No TTL — managed entirely through explicit add/remove calls.
 */
@Repository
@RequiredArgsConstructor
public class ActiveOrdersRedisRepository {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> removeActiveOrderScript;

    /**
     * Adds an orderId to the user's active-order set.
     * Creates the key if it does not yet exist (Redis SADD semantics).
     */
    public void add(String userId, String orderId) {
        redisTemplate.opsForSet().add(RedisKeys.activeOrders(userId), orderId);
    }

    /**
     * Removes an orderId from the user's active-order set and deletes the
     * entire key atomically if the set is now empty.
     *
     * Uses a Lua script to close the race where a concurrent SADD could land
     * between a plain SREM and a subsequent DEL, silently wiping the new entry.
     */
    public void remove(String userId, String orderId) {
        redisTemplate.execute(
                removeActiveOrderScript,
                Collections.singletonList(RedisKeys.activeOrders(userId)),
                orderId
        );
    }

    /**
     * Returns all active order IDs for the user.
     * Returns an empty set (never null) when the key does not exist.
     */
    public Set<String> getAll(String userId) {
        Set<String> result = redisTemplate.opsForSet().members(RedisKeys.activeOrders(userId));
        return result != null ? result : Collections.emptySet();
    }

    /**
     * Returns {@code true} if the given orderId is currently active for the user.
     * Backed by Redis SISMEMBER — O(1).
     */
    public boolean contains(String userId, String orderId) {
        Boolean member = redisTemplate.opsForSet().isMember(RedisKeys.activeOrders(userId), orderId);
        return Boolean.TRUE.equals(member);
    }

    /**
     * Returns the number of active orders the user currently has.
     * Returns 0 when the key does not exist.
     */
    public long count(String userId) {
        Long size = redisTemplate.opsForSet().size(RedisKeys.activeOrders(userId));
        return size != null ? size : 0L;
    }
}
