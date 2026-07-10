package com.sebet.order_service.cache.eviction;

import com.sebet.order_service.cache.keys.RedisKeys;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ScheduleType;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class CancelledOrderHotViewsCacheEvictionStrategyTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RedisScript<Long> script = mock(RedisScript.class);
    private final CancelledOrderHotViewsCacheEvictionStrategy strategy =
            new CancelledOrderHotViewsCacheEvictionStrategy(orderRepository, redisTemplate, script);

    @Test
    void cacheKeys_returnsAllCancellationHotViewKeys() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order(id)));

        assertThat(strategy.cacheKeys(id.toString())).containsExactly(
                RedisKeys.activeOrders("customer-1"),
                RedisKeys.storeActiveOrders("store-1"),
                RedisKeys.order(id.toString()),
                RedisKeys.orderTracking(id.toString()),
                RedisKeys.orderStatus(id.toString()),
                RedisKeys.orderTimeline(id.toString()),
                RedisKeys.orderProposals(id.toString())
        );
    }

    @Test
    void evict_executesAtomicCancellationHotViewScript() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));

        strategy.evict(id.toString());

        verify(redisTemplate).execute(
                script,
                List.of(
                        RedisKeys.activeOrders("customer-1"),
                        RedisKeys.storeActiveOrders("store-1"),
                        RedisKeys.order(id.toString()),
                        RedisKeys.orderTracking(id.toString()),
                        RedisKeys.orderStatus(id.toString()),
                        RedisKeys.orderTimeline(id.toString()),
                        RedisKeys.orderProposals(id.toString())
                ),
                id.toString()
        );
    }

    private OrderEntity order(UUID id) {
        OffsetDateTime now = OffsetDateTime.now();
        return OrderEntity.builder()
                .id(id)
                .customerId("customer-1")
                .storeId("store-1")
                .cartId("cart-1")
                .status(OrderStatus.CANCELLED)
                .scheduleType(ScheduleType.ASAP)
                .subtotalAmount(new BigDecimal("33000.00"))
                .itemDiscountAmount(BigDecimal.ZERO)
                .orderDiscountAmount(BigDecimal.ZERO)
                .deliveryFeeAmount(new BigDecimal("3000.00"))
                .totalAmount(new BigDecimal("36000.00"))
                .currency("UZS")
                .deliveryAddressJson("{\"street\":\"Amir Temur 25\"}")
                .deliveryLat(new BigDecimal("41.311100"))
                .deliveryLng(new BigDecimal("69.279700"))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
