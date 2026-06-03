package com.sebet.order_service.cache.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.VerificationCodeCacheDto;
import com.sebet.order_service.cache.keys.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * Cache 7 — order:verification:{orderId}  (STRING / JSON)
 *
 * Manages the short-lived delivery verification code for a single order.
 *
 * TTL: 30 minutes — sliding on save, not reset on read.
 * An expired key means the driver has not verified within the window;
 * the customer should contact support.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class VerificationCodeRedisRepository {

    static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Writes the verification code for an order.
     * Called by the OrderArrivedEventConsumer after generating the code.
     * Overwrites any existing entry (idempotent on retry).
     *
     * TODO: called from OrderArrivedEventConsumer — implement with Kafka layer
     *
     * @param orderId the order that has been arrived
     * @param dto     the generated code with its timestamp
     */
    public void save(String orderId, VerificationCodeCacheDto dto) {
        try {
            String json = objectMapper.writeValueAsString(dto);
            redisTemplate.opsForValue().set(
                    RedisKeys.orderVerification(orderId), json, TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize VerificationCodeCacheDto for orderId={}", orderId, e);
            throw new IllegalStateException(
                    "Redis serialization failed for verification code " + orderId, e);
        }
    }

    /**
     * Returns the verification code entry, or {@link Optional#empty()} on
     * cache miss (TTL expired or code not yet generated).
     *
     * @param orderId the order to look up
     */
    public Optional<VerificationCodeCacheDto> findById(String orderId) {
        String json = redisTemplate.opsForValue().get(RedisKeys.orderVerification(orderId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, VerificationCodeCacheDto.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize VerificationCodeCacheDto for orderId={}", orderId, e);
            return Optional.empty();
        }
    }

    /**
     * Stamps {@code verifiedAt} on an existing entry after the driver
     * submits the correct code.  Uses a read-modify-write — safe because
     * code verification is a single-writer operation gated by the
     * distributed lock on the driver endpoint.
     *
     * TODO: called from driver verify-code endpoint — implement with driver layer
     *
     * @param orderId    the order whose code was just verified
     * @param verifiedAt ISO-8601 timestamp of verification
     */
    public void markVerified(String orderId, String verifiedAt) {
        findById(orderId).ifPresent(dto -> {
            dto.setVerifiedAt(verifiedAt);
            save(orderId, dto);
        });
    }

    /**
     * Deletes the verification entry.
     * Called when the order transitions to DELIVERED or is cancelled.
     *
     * @param orderId the order whose verification code to remove
     */
    public void delete(String orderId) {
        redisTemplate.delete(RedisKeys.orderVerification(orderId));
    }
}
