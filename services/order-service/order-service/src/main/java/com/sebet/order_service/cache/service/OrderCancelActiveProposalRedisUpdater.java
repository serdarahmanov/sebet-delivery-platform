package com.sebet.order_service.cache.service;

import com.sebet.order_service.cache.eviction.CancelActiveProposalHotViewsEvictionStrategy;
import com.sebet.order_service.cache.keys.RedisKeys;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.shared.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderCancelActiveProposalRedisUpdater {

    static final String SOURCE_ACTION = "INTERNAL_CANCEL_ACTIVE_PROPOSAL";
    private static final String REASON = "CANCEL_ACTIVE_PROPOSAL";
    private static final String TIMELINE_STATUS_TO_REMOVE = "AWAITING_CUSTOMER_RESPONSE";
    private static final long C4_C6_TTL_SECONDS = 172800;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> cancelActiveProposalRedisUpdateScript;
    private final CancelActiveProposalHotViewsEvictionStrategy cancelActiveProposalHotViewsStrategy;
    private final OrderCacheEvictionService orderCacheEvictionService;

    public void apply(OrderEntity order, String idempotencyKey) {
        String orderId = order.getId().toString();
        try {
            String statusValue = OrderStatus.CONFIRMED.name()
                    + "|" + order.getCustomerId()
                    + "|" + order.getStoreId();

            redisTemplate.execute(
                    cancelActiveProposalRedisUpdateScript,
                    List.of(
                            RedisKeys.orderProposals(orderId),
                            RedisKeys.orderStatus(orderId),
                            RedisKeys.orderTimeline(orderId)
                    ),
                    statusValue,
                    String.valueOf(C4_C6_TTL_SECONDS),
                    String.valueOf(C4_C6_TTL_SECONDS),
                    TIMELINE_STATUS_TO_REMOVE
            );
        } catch (RuntimeException exception) {
            orderCacheEvictionService.requestEvictionAfterUpdateFailure(
                    orderId,
                    cancelActiveProposalHotViewsStrategy,
                    REASON,
                    SOURCE_ACTION,
                    idempotencyKey,
                    exception
            );
        }
    }
}
