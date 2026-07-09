package com.sebet.order_service.integration.order.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.order.event.OrderEventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCacheEvictionProjectionConsumer {

    private static final String CONTAINER_ID = "orderCacheEvictionConsumer";

    private static final TypeReference<OrderEventEnvelope<JsonNode>> ORDER_EVENT_TYPE =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;
    private final OrderCacheEvictionProjectionHandler handler;
    private final KafkaListenerEndpointRegistry registry;

    @KafkaListener(
            topics = "${order-service.kafka.order-events.topic}",
            groupId = "${order-service.kafka.order-events.group-id}",
            autoStartup = "${order-service.kafka.order-events.auto-startup:false}",
            id = CONTAINER_ID,
            containerFactory = "cacheEvictionContainerFactory"
    )
    public void consume(String payload, Acknowledgment ack) {
        OrderEventEnvelope<JsonNode> event = deserialize(payload);
        try {
            handler.handle(event);
            ack.acknowledge();
        } catch (RedisConnectionFailureException | QueryTimeoutException e) {
            log.warn("Redis unavailable during cache eviction; pausing consumer orderId={}", event.aggregateId(), e);
            registry.getListenerContainer(CONTAINER_ID).pause();
            // No ack: offset not committed, message replayed when container resumes.
        }
    }

    private OrderEventEnvelope<JsonNode> deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, ORDER_EVENT_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to deserialize order event", exception);
        }
    }
}
