package com.sebet.order_service.cache.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.OrderTimelineEntry;
import com.sebet.order_service.cache.keys.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Cache 6 — order:timeline:{orderId}  (LIST / JSON)
 *
 * Ordered log of customer-facing status transitions for a single order.
 * Each element is a JSON-serialised {@link OrderTimelineEntry}.
 *
 * A Redis LIST is used because:
 *  - RPUSH is O(1) — appending a new step is atomic and cheap.
 *  - LRANGE returns elements in insertion order — no sorting needed.
 *  - The list never has more than 4 entries (one per customer-facing step).
 *
 * Written by the status-transition handler on every customer-visible
 * status change.  Read once when the tracking screen mounts.
 *
 * TTL: 48 hours — reset on first write, matches Cache 2 (order snapshot).
 * The entire key is deleted when the order completes or is cancelled.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class OrderTimelineRedisRepository {

    static final Duration TTL = Duration.ofHours(48);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Appends a new timeline step to the end of the list.
     *
     * Should be called exactly once per customer-facing status transition.
     * The key's TTL is set on the first call (RPUSH auto-creates the list).
     *
     * @param orderId the order being updated
     * @param entry   the new status step with its timestamp
     */
    public void append(String orderId, OrderTimelineEntry entry) {
        String key = RedisKeys.orderTimeline(orderId);
        try {
            String json = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForList().rightPush(key, json);
            redisTemplate.expire(key, TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize OrderTimelineEntry for orderId={}", orderId, e);
            throw new IllegalStateException(
                    "Redis serialization failed for order timeline " + orderId, e);
        }
    }

    /**
     * Returns all timeline entries in insertion order (oldest first).
     * Returns an empty list on cache miss — caller should fall back to DB.
     *
     * @param orderId the order to look up
     */
    public List<OrderTimelineEntry> findAll(String orderId) {
        List<String> jsonList = redisTemplate.opsForList()
                .range(RedisKeys.orderTimeline(orderId), 0, -1);

        if (jsonList == null || jsonList.isEmpty()) {
            return Collections.emptyList();
        }

        return jsonList.stream()
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, OrderTimelineEntry.class);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to deserialize OrderTimelineEntry for orderId={}", orderId, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Deletes the entire timeline key.
     * Called when an order is delivered or cancelled.
     *
     * @param orderId the order whose timeline to remove
     */
    public void delete(String orderId) {
        redisTemplate.delete(RedisKeys.orderTimeline(orderId));
    }
}
