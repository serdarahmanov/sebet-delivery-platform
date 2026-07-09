package com.sebet.order_service.cache.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.eviction.ProposeChangesHotViewsEvictionStrategy;
import com.sebet.order_service.cache.keys.RedisKeys;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderProposalEntity;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ScheduleType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SuppressWarnings("unchecked")
class OrderProposeChangesRedisUpdaterTest {

    private static final String ITEMS_JSON =
            "[{\"productId\":\"p1\",\"productName\":\"Apples\",\"requestedQuantity\":3.0,\"unit\":\"KG\",\"availableQuantity\":1.0}]";
    private static final OffsetDateTime PROPOSED_AT = OffsetDateTime.parse("2026-07-09T10:00:00Z");

    private final UUID orderId = UUID.randomUUID();

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RedisScript<Long> script = mock(RedisScript.class);
    private final ProposeChangesHotViewsEvictionStrategy evictionStrategy =
            mock(ProposeChangesHotViewsEvictionStrategy.class);
    private final OrderCacheEvictionService orderCacheEvictionService = mock(OrderCacheEvictionService.class);

    private final OrderProposeChangesRedisUpdater updater = new OrderProposeChangesRedisUpdater(
            redisTemplate,
            objectMapper,
            script,
            evictionStrategy,
            orderCacheEvictionService
    );

    // eq(List.of(...)) gives compile-time type List<String>, which resolves the overload
    // to execute(RedisScript, List<K>, Object...) instead of the RedisSerializer overload.
    private List<String> expectedKeys() {
        return List.of(
                RedisKeys.orderProposals(orderId.toString()),
                RedisKeys.orderStatus(orderId.toString()),
                RedisKeys.orderTimeline(orderId.toString())
        );
    }

    @Test
    void apply_executesLuaScriptWithCorrectKeysAndStaticArgs() {
        updater.apply(order(), proposal(), "idem-1");

        verify(redisTemplate).execute(
                eq(script),
                eq(expectedKeys()),
                any(String.class),
                eq("3600"),
                eq("AWAITING_CUSTOMER_RESPONSE|customer-1|store-1"),
                eq("172800"),
                any(String.class),
                eq("172800")
        );
        verify(orderCacheEvictionService, never())
                .requestEvictionAfterUpdateFailure(any(), any(), any(), any(), any(), any());
    }

    @Test
    void apply_proposalJsonContainsOrderIdAndItems() throws Exception {
        ArgumentCaptor<String> proposalJsonCaptor = ArgumentCaptor.forClass(String.class);

        updater.apply(order(), proposal(), "idem-1");

        verify(redisTemplate).execute(
                eq(script), eq(expectedKeys()),
                proposalJsonCaptor.capture(),
                any(String.class), any(String.class), any(String.class), any(String.class), any(String.class)
        );
        JsonNode node = objectMapper.readTree(proposalJsonCaptor.getValue());
        assertThat(node.path("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(node.path("proposedAt").asText()).isEqualTo(PROPOSED_AT.toString());
        assertThat(node.path("items").isArray()).isTrue();
        assertThat(node.path("items").get(0).path("productId").asText()).isEqualTo("p1");
    }

    @Test
    void apply_timelineEntryJsonContainsAwaitingStatus() throws Exception {
        ArgumentCaptor<String> timelineCaptor = ArgumentCaptor.forClass(String.class);

        updater.apply(order(), proposal(), "idem-1");

        verify(redisTemplate).execute(
                eq(script), eq(expectedKeys()),
                any(String.class), any(String.class), any(String.class), any(String.class),
                timelineCaptor.capture(),
                any(String.class)
        );
        JsonNode node = objectMapper.readTree(timelineCaptor.getValue());
        assertThat(node.path("status").asText()).isEqualTo("AWAITING_CUSTOMER_RESPONSE");
        assertThat(node.path("occurredAt").asText()).isEqualTo(PROPOSED_AT.toString());
    }

    @Test
    void apply_requestsEvictionWhenRedisConnectionFails() {
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(redisTemplate).execute(
                        eq(script), eq(expectedKeys()),
                        any(String.class), any(String.class), any(String.class),
                        any(String.class), any(String.class), any(String.class)
                );

        updater.apply(order(), proposal(), "idem-1");

        verify(orderCacheEvictionService).requestEvictionAfterUpdateFailure(
                eq(orderId.toString()),
                same(evictionStrategy),
                eq("PROPOSE_CHANGES"),
                eq("STORE_PROPOSE_CHANGES"),
                eq("idem-1"),
                any(RedisConnectionFailureException.class)
        );
    }

    @Test
    void apply_forwardsNonRedisRuntimeFailureToEvictionService() {
        IllegalStateException nonRedisFailure = new IllegalStateException("serialization failed");
        doThrow(nonRedisFailure).when(redisTemplate).execute(
                eq(script), eq(expectedKeys()),
                any(String.class), any(String.class), any(String.class),
                any(String.class), any(String.class), any(String.class)
        );

        updater.apply(order(), proposal(), "idem-1");

        verify(orderCacheEvictionService).requestEvictionAfterUpdateFailure(
                eq(orderId.toString()),
                same(evictionStrategy),
                eq("PROPOSE_CHANGES"),
                eq("STORE_PROPOSE_CHANGES"),
                eq("idem-1"),
                same(nonRedisFailure)
        );
    }

    private OrderEntity order() {
        OffsetDateTime now = OffsetDateTime.now();
        return OrderEntity.builder()
                .id(orderId)
                .customerId("customer-1")
                .storeId("store-1")
                .cartId("cart-1")
                .status(OrderStatus.AWAITING_CUSTOMER_RESPONSE)
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

    private OrderProposalEntity proposal() {
        return OrderProposalEntity.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .storeId("store-1")
                .proposedAt(PROPOSED_AT)
                .itemsJson(ITEMS_JSON)
                .build();
    }
}
