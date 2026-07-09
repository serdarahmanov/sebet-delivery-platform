package com.sebet.order_service.integration.order.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sebet.order_service.cache.eviction.CacheEvictionStrategy;
import com.sebet.order_service.order.event.OrderEventEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderCacheEvictionProjectionHandlerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final CacheEvictionStrategy c2Strategy = mock(CacheEvictionStrategy.class);
    private final OrderCacheEvictionProjectionHandler handler;

    OrderCacheEvictionProjectionHandlerTest() {
        when(c2Strategy.cacheName()).thenReturn("C2");
        handler = new OrderCacheEvictionProjectionHandler(List.of(c2Strategy));
        clearInvocations(c2Strategy);
    }

    @Test
    void handle_skipsUnsupportedEvents() {
        handler.handle(event("OrderAccepted", UUID.randomUUID(), Map.of("cacheName", "C2")));

        verifyNoInteractions(c2Strategy);
    }

    @Test
    void handle_skipsGeneralDriverAssignmentEvents() {
        handler.handle(event("DriverAssigned", UUID.randomUUID(), Map.of("driverId", "driver-1")));

        verifyNoInteractions(c2Strategy);
    }

    @Test
    void handle_skipsWhenNoCacheNameStrategyRegistered() {
        UUID id = UUID.randomUUID();

        handler.handle(event("OrderCacheEvictionRequested", id, Map.of("cacheName", "C7")));

        verifyNoInteractions(c2Strategy);
    }

    @Test
    void handle_evictsC2ForOrderCacheEvictionRequested() {
        UUID id = UUID.randomUUID();

        handler.handle(event("OrderCacheEvictionRequested", id, Map.of("cacheName", "C2")));

        verify(c2Strategy).evict(id.toString());
    }

    @Test
    void handle_dispatchesToCorrectStrategyByName() {
        CacheEvictionStrategy c4Strategy = mock(CacheEvictionStrategy.class);
        when(c4Strategy.cacheName()).thenReturn("C4");
        OrderCacheEvictionProjectionHandler multiHandler =
                new OrderCacheEvictionProjectionHandler(List.of(c2Strategy, c4Strategy));
        clearInvocations(c2Strategy, c4Strategy);

        UUID id = UUID.randomUUID();
        multiHandler.handle(event("OrderCacheEvictionRequested", id, Map.of("cacheName", "C4")));

        verify(c4Strategy).evict(id.toString());
        verifyNoInteractions(c2Strategy);
    }

    private OrderEventEnvelope<JsonNode> event(String eventType, UUID orderId, Map<String, String> data) {
        return new OrderEventEnvelope<>(
                UUID.randomUUID().toString(),
                eventType,
                orderId.toString(),
                "Order",
                1,
                "2026-07-09T10:00:00Z",
                "order-service",
                objectMapper.valueToTree(data)
        );
    }
}
