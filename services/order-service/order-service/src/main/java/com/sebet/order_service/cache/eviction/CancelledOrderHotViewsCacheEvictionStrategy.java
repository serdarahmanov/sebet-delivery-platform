package com.sebet.order_service.cache.eviction;

import com.sebet.order_service.cache.keys.RedisKeys;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CancelledOrderHotViewsCacheEvictionStrategy implements CacheEvictionStrategy {

    public static final String CACHE_NAME = "CANCELLED_ORDER_HOT_VIEWS";

    private final OrderRepository orderRepository;
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> evictCancelledOrderHotViewsScript;

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
        return keys(order, aggregateId);
    }

    @Override
    public void evict(String aggregateId) {
        OrderEntity order = loadOrder(aggregateId);
        redisTemplate.execute(
                evictCancelledOrderHotViewsScript,
                keys(order, aggregateId),
                aggregateId
        );
    }

    private List<String> keys(OrderEntity order, String orderId) {
        return List.of(
                RedisKeys.activeOrders(order.getCustomerId()),
                RedisKeys.storeActiveOrders(order.getStoreId()),
                RedisKeys.order(orderId),
                RedisKeys.orderTracking(orderId),
                RedisKeys.orderStatus(orderId),
                RedisKeys.orderTimeline(orderId)
        );
    }

    private OrderEntity loadOrder(String orderId) {
        try {
            return orderRepository.findById(UUID.fromString(orderId))
                    .orElseThrow(() -> new OrderNotFoundException(orderId));
        } catch (IllegalArgumentException exception) {
            throw new OrderNotFoundException(orderId);
        }
    }
}
