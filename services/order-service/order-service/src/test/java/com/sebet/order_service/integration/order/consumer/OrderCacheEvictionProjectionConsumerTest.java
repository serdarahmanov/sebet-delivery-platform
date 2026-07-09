package com.sebet.order_service.integration.order.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sebet.order_service.order.event.OrderEventEnvelope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class OrderCacheEvictionProjectionConsumerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final OrderCacheEvictionProjectionHandler handler = mock(OrderCacheEvictionProjectionHandler.class);
    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final OrderCacheEvictionProjectionConsumer consumer =
            new OrderCacheEvictionProjectionConsumer(objectMapper, handler, registry);

    @Test
    void deserializesRawJsonEnvelopeAndDelegatesToHandler() throws Exception {
        OrderEventEnvelope<Map<String, String>> event = new OrderEventEnvelope<>(
                UUID.randomUUID().toString(),
                "OrderCacheEvictionRequested",
                UUID.randomUUID().toString(),
                "Order",
                3,
                "2026-07-09T10:00:00Z",
                "order-service",
                Map.of("cacheName", "C2")
        );

        consumer.consume(objectMapper.writeValueAsString(event), ack);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<OrderEventEnvelope<JsonNode>> captor =
                ArgumentCaptor.forClass((Class) OrderEventEnvelope.class);
        verify(handler).handle(captor.capture());
        verifyNoMoreInteractions(handler);

        assertThat(captor.getValue().eventId()).isEqualTo(event.eventId());
        assertThat(captor.getValue().eventType()).isEqualTo("OrderCacheEvictionRequested");
        assertThat(captor.getValue().aggregateId()).isEqualTo(event.aggregateId());
        verify(ack).acknowledge();
    }

    @Test
    void rejectsMalformedJsonBeforeHandler() {
        assertThatThrownBy(() -> consumer.consume("{not-json", ack))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to deserialize order event");

        verifyNoMoreInteractions(handler);
        verify(ack, never()).acknowledge();
    }

    @Test
    void pausesContainerAndSkipsAckOnRedisFailure() throws Exception {
        OrderEventEnvelope<Map<String, String>> event = new OrderEventEnvelope<>(
                UUID.randomUUID().toString(),
                "OrderCacheEvictionRequested",
                UUID.randomUUID().toString(),
                "Order",
                1,
                "2026-07-09T10:00:00Z",
                "order-service",
                Map.of("cacheName", "C2")
        );
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer("orderCacheEvictionConsumer")).thenReturn(container);
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                .when(handler).handle(org.mockito.ArgumentMatchers.any());

        consumer.consume(objectMapper.writeValueAsString(event), ack);

        verify(container).pause();
        verify(ack, never()).acknowledge();
    }

    @Test
    void pausesContainerAndSkipsAckOnRedisTimeout() throws Exception {
        OrderEventEnvelope<Map<String, String>> event = new OrderEventEnvelope<>(
                UUID.randomUUID().toString(),
                "OrderCacheEvictionRequested",
                UUID.randomUUID().toString(),
                "Order",
                1,
                "2026-07-09T10:00:00Z",
                "order-service",
                Map.of("cacheName", "C2")
        );
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer("orderCacheEvictionConsumer")).thenReturn(container);
        org.mockito.Mockito.doThrow(new QueryTimeoutException("redis command timed out"))
                .when(handler).handle(org.mockito.ArgumentMatchers.any());

        consumer.consume(objectMapper.writeValueAsString(event), ack);

        verify(container).pause();
        verify(ack, never()).acknowledge();
    }

    @Test
    void propagatesNonRedisRuntimeFailureWithoutAckOrPause() throws Exception {
        OrderEventEnvelope<Map<String, String>> event = new OrderEventEnvelope<>(
                UUID.randomUUID().toString(),
                "OrderCacheEvictionRequested",
                UUID.randomUUID().toString(),
                "Order",
                1,
                "2026-07-09T10:00:00Z",
                "order-service",
                Map.of("cacheName", "C2")
        );
        org.mockito.Mockito.doThrow(new IllegalStateException("handler bug"))
                .when(handler).handle(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> consumer.consume(objectMapper.writeValueAsString(event), ack))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("handler bug");

        verify(registry, never()).getListenerContainer("orderCacheEvictionConsumer");
        verify(ack, never()).acknowledge();
    }
}
