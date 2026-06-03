package com.sebet.order_service.cache.repository;

import com.sebet.order_service.cache.keys.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collections;

/**
 * Cache 5 — order:lock:{cartId}  (STRING / SETNX)
 *
 * Distributed lock that prevents multiple service instances from processing
 * the same cart concurrently.  Acquired before order creation begins and
 * released after the order record is written to the DB.
 *
 * Lock acquisition uses SET NX EX atomically — no two instances can hold the
 * same lock simultaneously.  If the holder crashes mid-creation the 30-second
 * TTL ensures automatic release without manual intervention.
 */
@Repository
@RequiredArgsConstructor
public class OrderLockRedisRepository {

    /**
     * Self-release TTL.  If the holder crashes before calling {@link #release},
     * the lock is automatically dropped after 30 seconds.
     */
    static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> releaseLockScript;

    /**
     * Attempts to acquire the distributed lock for the given cartId.
     *
     * Uses Redis {@code SET key value NX EX seconds} which is atomic — the
     * existence check and the write happen in a single command so no two
     * callers can both see the key as absent.
     *
     * @param cartId     the cart being processed
     * @param instanceId a unique identifier for this service instance
     *                   (e.g. {@code "ord-service-instance-2"})
     * @return {@code true} if the lock was acquired by this call,
     *         {@code false} if it is already held by another instance
     */
    public boolean tryAcquire(String cartId, String instanceId) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(RedisKeys.orderLock(cartId), instanceId, LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * Releases the lock only when the current holder matches {@code instanceId}.
     *
     * Uses a Lua script (check-then-delete) to make the release atomic.
     * Without the Lua guard, a race could occur: lock expires → re-acquired by
     * another instance → original instance deletes the new lock.
     *
     * @param cartId     the cart whose lock to release
     * @param instanceId must match the value stored at {@link #tryAcquire} time
     * @return {@code true} if this call released the lock,
     *         {@code false} if the lock was not held by this instance
     *         (e.g. it expired and was re-acquired)
     */
    public boolean release(String cartId, String instanceId) {
        Long result = redisTemplate.execute(
                releaseLockScript,
                Collections.singletonList(RedisKeys.orderLock(cartId)),
                instanceId
        );
        return Long.valueOf(1L).equals(result);
    }

    /**
     * Returns {@code true} if a lock key currently exists for the cartId,
     * regardless of which instance holds it.  Useful for fast pre-flight
     * checks before entering the lock acquisition path.
     */
    public boolean isLocked(String cartId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.orderLock(cartId)));
    }
}
