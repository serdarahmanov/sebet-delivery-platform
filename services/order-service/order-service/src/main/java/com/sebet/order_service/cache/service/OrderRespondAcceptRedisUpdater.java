package com.sebet.order_service.cache.service;

import com.sebet.order_service.cache.eviction.RespondAcceptHotViewsEvictionStrategy;
import com.sebet.order_service.cache.keys.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderRespondAcceptRedisUpdater {

    static final String SOURCE_ACTION = "CUSTOMER_RESPOND_ACCEPT";
    private static final String REASON = "PROPOSAL_ACCEPTED";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> respondAcceptRedisUpdateScript;
    private final RespondAcceptHotViewsEvictionStrategy respondAcceptHotViewsStrategy;
    private final OrderCacheEvictionService orderCacheEvictionService;

    public void apply(String orderId, String idempotencyKey) {
        try {
            redisTemplate.execute(
                    respondAcceptRedisUpdateScript,
                    List.of(RedisKeys.orderProposals(orderId))
            );
        } catch (RuntimeException e) {
            orderCacheEvictionService.requestEvictionAfterUpdateFailure(
                    orderId,
                    respondAcceptHotViewsStrategy,
                    REASON,
                    SOURCE_ACTION,
                    idempotencyKey,
                    e
            );
        }
    }
}
