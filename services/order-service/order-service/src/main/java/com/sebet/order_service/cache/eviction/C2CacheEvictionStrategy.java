package com.sebet.order_service.cache.eviction;

import com.sebet.order_service.cache.keys.RedisKeys;
import com.sebet.order_service.cache.repository.OrderRedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class C2CacheEvictionStrategy implements CacheEvictionStrategy {

    private final OrderRedisRepository orderRedisRepository;

    @Override
    public String cacheName() {
        return "C2";
    }

    @Override
    public String cacheKey(String aggregateId) {
        return RedisKeys.order(aggregateId);
    }

    @Override
    public void evict(String aggregateId) {
        orderRedisRepository.delete(aggregateId);
    }
}
