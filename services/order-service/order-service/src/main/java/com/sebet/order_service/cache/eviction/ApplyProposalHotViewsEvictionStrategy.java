package com.sebet.order_service.cache.eviction;

import com.sebet.order_service.cache.keys.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ApplyProposalHotViewsEvictionStrategy implements CacheEvictionStrategy {

    public static final String CACHE_NAME = "APPLY_PROPOSAL_HOT_VIEWS";

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
        return List.of(
                RedisKeys.order(aggregateId),
                RedisKeys.orderProposals(aggregateId),
                RedisKeys.orderStatus(aggregateId),
                RedisKeys.orderTimeline(aggregateId)
        );
    }

    @Override
    public void evict(String aggregateId) {
        redisTemplate.delete(cacheKeys(aggregateId));
    }
}
