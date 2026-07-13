package com.sebet.order_service.cache.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.DeliveryAddress;
import com.sebet.order_service.cache.dto.RedisOrder;
import com.sebet.order_service.cache.eviction.ScheduledUpdateHotViewsEvictionStrategy;
import com.sebet.order_service.cache.keys.RedisKeys;
import com.sebet.order_service.cache.repository.OrderRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Applies Redis side-effects after a customer updates a scheduled order.
 *
 * A single Lua script atomically handles both caches:
 *   Cache 1c (store:scheduled_orders:{storeId} ZSET) — score updated when
 *             scheduledFor changes so the transition job and store queries stay correct.
 *   Cache 2  (order:{orderId} STRING) — snapshot updated in-place when address
 *             or phone number changes; KEEPTTL preserves the existing TTL.
 *
 * If Redis is unavailable the fallback writes an OrderCacheEvictionRequested event
 * so ScheduledUpdateHotViewsEvictionStrategy can retry once Redis recovers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderScheduledUpdateRedisWriter {

    private static final String REASON = "SCHEDULED_ORDER_UPDATED";
    private static final String SOURCE_ACTION = "CUSTOMER_UPDATE_SCHEDULED";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> updateScheduledOrderRedisScript;
    private final OrderRedisRepository orderRedisRepository;
    private final OrderCacheEvictionService orderCacheEvictionService;
    private final ScheduledUpdateHotViewsEvictionStrategy scheduledUpdateHotViewsStrategy;
    private final ObjectMapper objectMapper;

    /**
     * @param storeId            store that owns the order
     * @param orderId            the order being updated
     * @param newScheduledFor    non-null when the delivery window start changed
     * @param newDeliveryAddress non-null when the delivery address changed
     * @param newPhoneNumber     non-null when the phone number changed
     * @param idempotencyKey     used for fallback event deduplication
     */
    public void apply(
            String storeId,
            String orderId,
            Instant newScheduledFor,
            DeliveryAddress newDeliveryAddress,
            String newPhoneNumber,
            String idempotencyKey
    ) {
        String scoreArg = newScheduledFor != null
                ? String.valueOf(newScheduledFor.toEpochMilli())
                : "";

        String snapshotArg = buildUpdatedSnapshotJson(orderId, newDeliveryAddress, newPhoneNumber);

        try {
            redisTemplate.execute(
                    updateScheduledOrderRedisScript,
                    List.of(
                            RedisKeys.storeScheduledOrders(storeId),
                            RedisKeys.order(orderId)
                    ),
                    orderId, scoreArg, snapshotArg
            );
        } catch (RuntimeException e) {
            orderCacheEvictionService.requestEvictionAfterUpdateFailure(
                    orderId, scheduledUpdateHotViewsStrategy,
                    REASON, SOURCE_ACTION, idempotencyKey, e
            );
        }
    }

    private String buildUpdatedSnapshotJson(
            String orderId,
            DeliveryAddress newDeliveryAddress,
            String newPhoneNumber
    ) {
        if (newDeliveryAddress == null && newPhoneNumber == null) {
            return "";
        }
        Optional<RedisOrder> current = orderRedisRepository.findById(orderId);
        if (current.isEmpty()) {
            return "";
        }
        RedisOrder snapshot = current.get();
        DeliveryAddress address = snapshot.getDeliveryAddress() != null
                ? snapshot.getDeliveryAddress()
                : new DeliveryAddress();

        if (newDeliveryAddress != null) {
            address.setStreet(newDeliveryAddress.getStreet());
            address.setCity(newDeliveryAddress.getCity());
            address.setLat(newDeliveryAddress.getLat());
            address.setLng(newDeliveryAddress.getLng());
        }
        if (newPhoneNumber != null) {
            address.setPhoneNumber(newPhoneNumber);
        }
        snapshot.setDeliveryAddress(address);
        snapshot.setUpdatedAt(OffsetDateTime.now().toString());

        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize updated Cache 2 snapshot for orderId={}, skipping C2 update", orderId, e);
            return "";
        }
    }
}
