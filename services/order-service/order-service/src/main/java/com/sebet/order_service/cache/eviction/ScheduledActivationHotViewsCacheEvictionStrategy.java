package com.sebet.order_service.cache.eviction;

import com.sebet.order_service.cache.keys.RedisKeys;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ScheduledActivationHotViewsCacheEvictionStrategy implements CacheEvictionStrategy {

    public static final String CACHE_NAME = "SCHEDULED_ACTIVATION_HOT_VIEWS";

    private static final Duration C1B_TTL = Duration.ofHours(48);
    private static final Duration C4_TTL = Duration.ofHours(48);

    private final OrderRepository orderRepository;
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> activateScheduledOrderRedisUpdateScript;

    @Override
    public String cacheName() {
        return CACHE_NAME;
    }

    @Override
    public String cacheKey(String aggregateId) {
        return RedisKeys.orderStatus(aggregateId);
    }

    @Override
    public List<String> cacheKeys(String aggregateId) {
        OrderEntity order = loadOrder(aggregateId);
        return keys(order, aggregateId);
    }

    @Override
    public void evict(String aggregateId) {
        OrderEntity order = loadOrder(aggregateId);
        apply(order);
    }

    public void apply(OrderEntity order) {
        if (!isActiveStatus(order.getStatus())) {
            return;
        }
        String orderId = order.getId().toString();
        redisTemplate.execute(
                activateScheduledOrderRedisUpdateScript,
                keys(order, orderId),
                orderId,
                statusValue(order),
                String.valueOf(C1B_TTL.toSeconds()),
                String.valueOf(C4_TTL.toSeconds())
        );
    }

    private List<String> keys(OrderEntity order, String orderId) {
        return List.of(
                RedisKeys.storeScheduledOrders(order.getStoreId()),
                RedisKeys.activeOrders(order.getCustomerId()),
                RedisKeys.storeActiveOrders(order.getStoreId()),
                RedisKeys.orderStatus(orderId)
        );
    }

    private String statusValue(OrderEntity order) {
        return order.getStatus().name() + "|" + order.getCustomerId() + "|" + order.getStoreId();
    }

    private boolean isActiveStatus(OrderStatus status) {
        return switch (status) {
            case PENDING,
                 CONFIRMED,
                 READY_FOR_PICKUP,
                 OUT_FOR_DELIVERY,
                 ARRIVED,
                 AWAITING_CUSTOMER_RESPONSE -> true;
            default -> false;
        };
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
