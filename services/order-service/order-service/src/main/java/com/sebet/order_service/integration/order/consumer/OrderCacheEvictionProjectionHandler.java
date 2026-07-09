package com.sebet.order_service.integration.order.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.sebet.order_service.cache.eviction.CacheEvictionStrategy;
import com.sebet.order_service.order.event.OrderEventEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class OrderCacheEvictionProjectionHandler {

    private static final String ORDER_CACHE_EVICTION_REQUESTED = "OrderCacheEvictionRequested";

    private final Map<String, CacheEvictionStrategy> strategies;

    public OrderCacheEvictionProjectionHandler(List<CacheEvictionStrategy> strategies) {
        this.strategies = strategies.stream()
                .collect(Collectors.toMap(CacheEvictionStrategy::cacheName, Function.identity()));
    }

    public void handle(OrderEventEnvelope<JsonNode> event) {
        if (!ORDER_CACHE_EVICTION_REQUESTED.equals(event.eventType())) {
            return;
        }

        JsonNode data = event.data();
        if (data == null) {
            return;
        }

        String cacheName = data.path("cacheName").asText();
        CacheEvictionStrategy strategy = strategies.get(cacheName);
        if (strategy == null) {
            log.warn("No eviction strategy for cacheName={} orderId={}", cacheName, event.aggregateId());
            return;
        }

        strategy.evict(event.aggregateId());
        log.debug("Evicted cacheName={} orderId={}", cacheName, event.aggregateId());
    }
}
