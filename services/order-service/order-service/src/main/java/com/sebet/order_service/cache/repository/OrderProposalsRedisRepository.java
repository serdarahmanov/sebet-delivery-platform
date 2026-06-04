package com.sebet.order_service.cache.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.OrderProposalsCacheDto;
import com.sebet.order_service.cache.keys.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * Cache 8 — order:proposals:{orderId}  (STRING / JSON)
 *
 * Stores the item-level change proposals submitted by the store while the order
 * is in {@code AWAITING_CUSTOMER_RESPONSE} status.
 *
 * TTL is set to 1 hour, matching the customer response window.
 * The timeout job queries orders still in {@code AWAITING_CUSTOMER_RESPONSE}
 * status after 1 hour and cancels them; this key may already be expired by then.
 */
@Repository
@RequiredArgsConstructor
public class OrderProposalsRedisRepository {

    /** Customer response window — must match the timeout job's threshold. */
    static final Duration PROPOSALS_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Saves the proposals for an order, overwriting any prior entry.
     * TTL is (re)set to {@link #PROPOSALS_TTL} on every write.
     *
     * @throws IllegalStateException if JSON serialisation fails
     */
    public void save(OrderProposalsCacheDto proposals) {
        try {
            String json = objectMapper.writeValueAsString(proposals);
            redisTemplate.opsForValue().set(
                    RedisKeys.orderProposals(proposals.getOrderId()),
                    json,
                    PROPOSALS_TTL
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialise proposals for order " + proposals.getOrderId(), e);
        }
    }

    /**
     * Returns the stored proposals for the given order, or {@link Optional#empty()}
     * if the key has expired or was never written.
     */
    public Optional<OrderProposalsCacheDto> find(String orderId) {
        String json = redisTemplate.opsForValue().get(RedisKeys.orderProposals(orderId));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, OrderProposalsCacheDto.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to deserialise proposals for order " + orderId, e);
        }
    }

    /**
     * Deletes the proposal key explicitly — called after the customer responds
     * or after the timeout job cancels the order.
     */
    public void delete(String orderId) {
        redisTemplate.delete(RedisKeys.orderProposals(orderId));
    }

    /**
     * Returns {@code true} if a proposal entry currently exists for the order.
     */
    public boolean exists(String orderId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.orderProposals(orderId)));
    }
}
