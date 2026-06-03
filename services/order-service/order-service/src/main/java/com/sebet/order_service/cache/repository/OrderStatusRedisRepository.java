package com.sebet.order_service.cache.repository;

import com.sebet.order_service.cache.keys.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * Cache 4 — order:status:{orderId}  (STRING plain)
 *
 * Stores only the current status string for an order.
 * Exists separately from order:tracking so that status-only reads
 * (kitchen screen polling, push notification triggers) are cheap and
 * do not load the full GPS tracking payload.
 *
 * Invariant: this key and the {@code status} field inside
 * order:tracking:{orderId} must always be updated together.
 * Callers are responsible for calling both repositories in the same
 * logical operation — there is no two-phase commit between them.
 *
 * TTL: 48 hours — matches order:{orderId} so both age out together.
 */
@Repository
@RequiredArgsConstructor
public class OrderStatusRedisRepository {

    /** Matches the TTL of Cache 2 (order:{orderId}) so both expire together. */
    static final Duration TTL = Duration.ofHours(48);

    private final StringRedisTemplate redisTemplate;

    /**
     * Writes or overwrites the status string for an order.
     * Must always be called in sync with {@link OrderTrackingRedisRepository#save}
     * so Cache 3 and Cache 4 never hold conflicting status values.
     *
     * @param orderId the order whose status is changing
     * @param status  the new status string, e.g. {@code "out_for_delivery"}
     */
    public void save(String orderId, String status) {
        redisTemplate.opsForValue().set(RedisKeys.orderStatus(orderId), status, TTL);
    }

    /**
     * Returns the raw status string, or {@link Optional#empty()} on cache miss.
     *
     * @param orderId the order to look up
     */
    public Optional<String> findById(String orderId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(RedisKeys.orderStatus(orderId)));
    }

    /** Deletes the status entry — called when an order is completed or cancelled. */
    public void delete(String orderId) {
        redisTemplate.delete(RedisKeys.orderStatus(orderId));
    }
}
