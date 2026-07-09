package com.sebet.order_service.cache.eviction;

public interface CacheEvictionStrategy {

    String cacheName();

    String cacheKey(String aggregateId);

    void evict(String aggregateId);
}
