package com.sebet.order_service.cache.repository;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.sebet.order_service.cache.dto.RedisOrder;
import com.sebet.order_service.cache.keys.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

/**
 * Cache 2 — order:{orderId}  (STRING / JSON)
 *
 * Full static snapshot of an order.  Written once on order creation and
 * never updated during delivery.  Serves the REST API response when the
 * user opens the tracking screen.
 *
 * TTL: 48 hours.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class OrderRedisRepository {

    static final Duration TTL = Duration.ofHours(48);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Serializes and writes the full order snapshot.
     * Called exactly once after the order record is persisted to the DB.
     * TTL: 48 hours.
     *
     * @throws IllegalStateException if Jackson serialization fails (should never happen
     *                               in practice; indicates a misconfigured ObjectMapper
     *                               or an un-serializable field type)
     */
    public void save(RedisOrder order) {
        try {
            String json = objectMapper.writeValueAsString(order);
            redisTemplate.opsForValue().set(RedisKeys.order(order.getOrderId()), json, TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize RedisOrder for orderId={}", order.getOrderId(), e);
            throw new IllegalStateException(
                    "Redis serialization failed for order " + order.getOrderId(), e);
        }
    }

    /**
     * Returns the full order snapshot, or {@link Optional#empty()} on cache miss
     * or deserialization error.  A deserialization error is logged but not
     * re-thrown so the caller can fall back to the DB.
     */
    public Optional<RedisOrder> findById(String orderId) {
        String json = redisTemplate.opsForValue().get(RedisKeys.order(orderId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, RedisOrder.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize RedisOrder for orderId={}", orderId, e);
            return Optional.empty();
        }
    }

    /** Deletes the order snapshot. Called when an order is fully archived or purged. */
    public void delete(String orderId) {
        redisTemplate.delete(RedisKeys.order(orderId));
    }
}
