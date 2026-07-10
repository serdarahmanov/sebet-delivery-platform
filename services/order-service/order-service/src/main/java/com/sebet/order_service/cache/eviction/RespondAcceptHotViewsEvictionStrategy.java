package com.sebet.order_service.cache.eviction;

import com.sebet.order_service.cache.keys.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RespondAcceptHotViewsEvictionStrategy implements CacheEvictionStrategy {

    public static final String CACHE_NAME = "RESPOND_ACCEPT_HOT_VIEWS";

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
    public void evict(String aggregateId) {
        redisTemplate.delete(cacheKey(aggregateId));
    }
}
