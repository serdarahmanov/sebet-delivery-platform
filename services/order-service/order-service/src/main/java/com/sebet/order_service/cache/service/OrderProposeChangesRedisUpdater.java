package com.sebet.order_service.cache.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.OrderProposalsCacheDto;
import com.sebet.order_service.cache.dto.OrderTimelineEntry;
import com.sebet.order_service.cache.eviction.ProposeChangesHotViewsEvictionStrategy;
import com.sebet.order_service.cache.keys.RedisKeys;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderProposalEntity;
import com.sebet.order_service.shared.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderProposeChangesRedisUpdater {

    static final String SOURCE_ACTION = "STORE_PROPOSE_CHANGES";
    private static final String REASON = "PROPOSE_CHANGES";
    private static final long C8_TTL_SECONDS = 3600;
    private static final long C4_C6_TTL_SECONDS = 172800;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RedisScript<Long> proposeChangesRedisUpdateScript;
    private final ProposeChangesHotViewsEvictionStrategy proposeChangesHotViewsStrategy;
    private final OrderCacheEvictionService orderCacheEvictionService;

    public void apply(OrderEntity order, OrderProposalEntity proposal, String idempotencyKey) {
        String orderId = order.getId().toString();
        try {
            String proposalJson = buildProposalJson(order, proposal);
            String statusValue = OrderStatus.AWAITING_CUSTOMER_RESPONSE.name()
                    + "|" + order.getCustomerId()
                    + "|" + order.getStoreId();
            String timelineEntryJson = serialize(
                    new OrderTimelineEntry("AWAITING_CUSTOMER_RESPONSE", proposal.getProposedAt().toString()));

            redisTemplate.execute(
                    proposeChangesRedisUpdateScript,
                    List.of(
                            RedisKeys.orderProposals(orderId),
                            RedisKeys.orderStatus(orderId),
                            RedisKeys.orderTimeline(orderId)
                    ),
                    proposalJson,
                    String.valueOf(C8_TTL_SECONDS),
                    statusValue,
                    String.valueOf(C4_C6_TTL_SECONDS),
                    timelineEntryJson,
                    String.valueOf(C4_C6_TTL_SECONDS)
            );
        } catch (RuntimeException e) {
            orderCacheEvictionService.requestEvictionAfterUpdateFailure(
                    orderId,
                    proposeChangesHotViewsStrategy,
                    REASON,
                    SOURCE_ACTION,
                    idempotencyKey,
                    e
            );
        }
    }

    private String buildProposalJson(OrderEntity order, OrderProposalEntity proposal) {
        try {
            List<OrderProposalsCacheDto.ProposedItem> items = objectMapper.readValue(
                    proposal.getItemsJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, OrderProposalsCacheDto.ProposedItem.class)
            );
            return serialize(OrderProposalsCacheDto.builder()
                    .orderId(order.getId().toString())
                    .proposedAt(proposal.getProposedAt().toString())
                    .items(items)
                    .build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to build Cache 8 proposal JSON for orderId=" + order.getId(), e);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Redis value for propose-changes update", e);
        }
    }
}
