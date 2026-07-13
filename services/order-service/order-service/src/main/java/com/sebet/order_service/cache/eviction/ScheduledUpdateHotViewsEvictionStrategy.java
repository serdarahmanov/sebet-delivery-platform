package com.sebet.order_service.cache.eviction;

import com.sebet.order_service.cache.keys.RedisKeys;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Fallback eviction strategy for scheduled-order updates.
 *
 * Invoked by the OrderCacheEvictionProjectionConsumer when the primary Lua script
 * in OrderScheduledUpdateRedisWriter failed with a recoverable Redis error and the
 * event was replayed after Redis recovered.
 *
 * Recovery behaviour:
 *   Cache 1c — ZREM + ZADD using the scheduledFor value already persisted in the DB
 *              (the DB transaction committed before Redis failed, so the value is correct).
 *   Cache 2  — deleted so the next read rebuilds a fresh snapshot from the DB.
 *              Re-merging the changed fields in the fallback path is unnecessary;
 *              a clean rebuild is simpler and equally correct.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledUpdateHotViewsEvictionStrategy implements CacheEvictionStrategy {

    public static final String CACHE_NAME = "SCHEDULED_UPDATE_HOT_VIEWS";

    private final OrderRepository orderRepository;
    private final StringRedisTemplate redisTemplate;

    @Override
    public String cacheName() {
        return CACHE_NAME;
    }

    @Override
    public String cacheKey(String aggregateId) {
        return RedisKeys.order(aggregateId);
    }

    @Override
    public List<String> cacheKeys(String aggregateId) {
        OrderEntity order = loadOrder(aggregateId);
        return List.of(
                RedisKeys.storeScheduledOrders(order.getStoreId()),
                RedisKeys.order(aggregateId)
        );
    }

    @Override
    public void evict(String aggregateId) {
        OrderEntity order = loadOrder(aggregateId);
        if (order.getScheduledFor() == null) {
            log.warn("scheduledFor is null for orderId={} — skipping Cache 1c update, evicting Cache 2 only", aggregateId);
            redisTemplate.delete(RedisKeys.order(aggregateId));
            return;
        }
        String scheduledKey = RedisKeys.storeScheduledOrders(order.getStoreId());
        double score = (double) order.getScheduledFor().toInstant().toEpochMilli();
        redisTemplate.opsForZSet().remove(scheduledKey, aggregateId);
        redisTemplate.opsForZSet().add(scheduledKey, aggregateId, score);
        redisTemplate.delete(RedisKeys.order(aggregateId));
    }

    private OrderEntity loadOrder(String orderId) {
        try {
            return orderRepository.findById(UUID.fromString(orderId))
                    .orElseThrow(() -> new OrderNotFoundException(orderId));
        } catch (IllegalArgumentException e) {
            throw new OrderNotFoundException(orderId);
        }
    }
}
