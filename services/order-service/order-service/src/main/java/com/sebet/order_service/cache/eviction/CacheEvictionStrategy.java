package com.sebet.order_service.cache.eviction;

import java.util.List;

public interface CacheEvictionStrategy {

    String cacheName();

    String cacheKey(String aggregateId);

    default List<String> cacheKeys(String aggregateId) {
        return List.of(cacheKey(aggregateId));
    }

    void evict(String aggregateId);
}
