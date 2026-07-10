package com.sebet.order_service.cache.service;

import com.sebet.order_service.cache.eviction.CancelActiveProposalHotViewsEvictionStrategy;
import com.sebet.order_service.cache.keys.RedisKeys;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ScheduleType;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SuppressWarnings("unchecked")
class OrderCancelActiveProposalRedisUpdaterTest {

    private final UUID orderId = UUID.randomUUID();

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RedisScript<Long> script = mock(RedisScript.class);
    private final CancelActiveProposalHotViewsEvictionStrategy evictionStrategy =
            mock(CancelActiveProposalHotViewsEvictionStrategy.class);
    private final OrderCacheEvictionService orderCacheEvictionService = mock(OrderCacheEvictionService.class);

    private final OrderCancelActiveProposalRedisUpdater updater = new OrderCancelActiveProposalRedisUpdater(
            redisTemplate,
            script,
            evictionStrategy,
            orderCacheEvictionService
    );

    private List<String> expectedKeys() {
        return List.of(
                RedisKeys.orderProposals(orderId.toString()),
                RedisKeys.orderStatus(orderId.toString()),
                RedisKeys.orderTimeline(orderId.toString())
        );
    }

    @Test
    void apply_executesLuaScriptWithCorrectKeysAndArgs() {
        updater.apply(order(), "idem-1");

        verify(redisTemplate).execute(
                eq(script),
                eq(expectedKeys()),
                eq("CONFIRMED|customer-1|store-1"),
                eq("172800"),
                eq("172800"),
                eq("AWAITING_CUSTOMER_RESPONSE")
        );
        verify(orderCacheEvictionService, never())
                .requestEvictionAfterUpdateFailure(any(), any(), any(), any(), any(), any());
    }

    @Test
    void apply_requestsEvictionWhenRedisConnectionFails() {
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(redisTemplate).execute(
                        eq(script),
                        eq(expectedKeys()),
                        eq("CONFIRMED|customer-1|store-1"),
                        eq("172800"),
                        eq("172800"),
                        eq("AWAITING_CUSTOMER_RESPONSE")
                );

        updater.apply(order(), "idem-1");

        verify(orderCacheEvictionService).requestEvictionAfterUpdateFailure(
                eq(orderId.toString()),
                same(evictionStrategy),
                eq("CANCEL_ACTIVE_PROPOSAL"),
                eq("INTERNAL_CANCEL_ACTIVE_PROPOSAL"),
                eq("idem-1"),
                any(RedisConnectionFailureException.class)
        );
    }

    private OrderEntity order() {
        OffsetDateTime now = OffsetDateTime.now();
        return OrderEntity.builder()
                .id(orderId)
                .customerId("customer-1")
                .storeId("store-1")
                .cartId("cart-1")
                .status(OrderStatus.CONFIRMED)
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
