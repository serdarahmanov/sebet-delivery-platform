package com.sebet.order_service.cache.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.RedisOrderTracking;
import com.sebet.order_service.cache.keys.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * Cache 3 — order:tracking:{orderId}  (STRING / JSON)
 *
 * Live delivery state written by the live-tracking service every few seconds
 * as the driver moves.  Read by the WebSocket server to push updates to the
 * tracking screen.
 *
 * TTL: 5 minutes — sliding, reset on every write.
 * An expired or absent key signals that the driver app has gone silent.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class OrderTrackingRedisRepository {

    /**
     * Sliding TTL — reset on every write.
     * Expiry means no GPS update has arrived in 5 minutes (driver app silent).
     */
    static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Saves (or overwrites) the live tracking snapshot.
     * The TTL is always reset to 5 minutes, making it a sliding window.
     * Must be called in sync with {@link OrderStatusRedisRepository#save}
     * so Cache 3 and Cache 4 never diverge on the {@code status} field.
     *
     * @throws IllegalStateException if Jackson serialization fails
     */
    public void save(String orderId, RedisOrderTracking tracking) {
        try {
            String json = objectMapper.writeValueAsString(tracking);
            redisTemplate.opsForValue().set(RedisKeys.orderTracking(orderId), json, TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize RedisOrderTracking for orderId={}", orderId, e);
            throw new IllegalStateException(
                    "Redis serialization failed for order tracking " + orderId, e);
        }
    }

    /**
     * Returns the current live tracking snapshot.
     * Returns {@link Optional#empty()} on cache miss (TTL expired or never written)
     * or deserialization error.
     */
    public Optional<RedisOrderTracking> findById(String orderId) {
        String json = redisTemplate.opsForValue().get(RedisKeys.orderTracking(orderId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, RedisOrderTracking.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize RedisOrderTracking for orderId={}", orderId, e);
            return Optional.empty();
        }
    }

    /** Deletes the tracking entry — called when an order is completed or cancelled. */
    public void delete(String orderId) {
        redisTemplate.delete(RedisKeys.orderTracking(orderId));
    }
}
