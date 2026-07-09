package com.sebet.order_service.cache.eviction;

import com.sebet.order_service.cache.keys.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ProposeChangesHotViewsEvictionStrategy implements CacheEvictionStrategy {

    public static final String CACHE_NAME = "PROPOSE_CHANGES_HOT_VIEWS";

    private final StringRedisTemplate redisTemplate;

    @Override
    public String cacheName() {
        return CACHE_NAME;
    }

    @Override
    public String cacheKey(String aggregateId) {
        return RedisKeys.orderProposals(aggregateId);
    }

    @Override
    public List<String> cacheKeys(String aggregateId) {
        return List.of(
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
