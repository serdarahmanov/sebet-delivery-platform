package com.sebet.order_service.cache.repository;

import com.sebet.order_service.cache.keys.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * Cache 4 — order:status:{orderId}  (STRING "STATUS|userId|storeId")
 *
 * Stores the current status, owner userId, and owner storeId together so
 * customer/store ownership can be verified in the same single read, without a
 * separate C2 lookup.
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

    public record Entry(String status, String userId, String storeId) {}

    /** Matches the TTL of Cache 2 (order:{orderId}) so both expire together. */
    static final Duration TTL = Duration.ofHours(48);

    private final StringRedisTemplate redisTemplate;

    /**
     * Writes or overwrites the status entry for an order.
     * Must always be called in sync with {@link OrderTrackingRedisRepository#save}
     * so Cache 3 and Cache 4 never hold conflicting status values.
     */
    public void save(String orderId, String userId, String storeId, String status) {
        redisTemplate.opsForValue().set(RedisKeys.orderStatus(orderId), status + "|" + userId + "|" + storeId, TTL);
    }

    /**
     * Returns the status entry, or {@link Optional#empty()} on cache miss.
     */
    public Optional<Entry> findById(String orderId) {
        String raw = redisTemplate.opsForValue().get(RedisKeys.orderStatus(orderId));
        if (raw == null) return Optional.empty();
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 3) return Optional.empty();
        return Optional.of(new Entry(parts[0], parts[1], parts[2]));
    }

    /** Deletes the status entry — called when an order is completed or cancelled. */
    public void delete(String orderId) {
        redisTemplate.delete(RedisKeys.orderStatus(orderId));
    }
}
