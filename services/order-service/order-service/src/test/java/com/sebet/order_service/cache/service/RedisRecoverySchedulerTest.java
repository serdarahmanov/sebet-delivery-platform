package com.sebet.order_service.cache.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRecoverySchedulerTest {

    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RedisRecoveryScheduler scheduler = new RedisRecoveryScheduler(registry, redisTemplate);

    @Test
    void resumeIfRedisHealthy_doesNothingWhenContainerIsNotPaused() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer("orderCacheEvictionConsumer")).thenReturn(container);
        when(container.isPauseRequested()).thenReturn(false);

        scheduler.resumeIfRedisHealthy();

        verify(redisTemplate, never()).hasKey("__health__");
        verify(container, never()).resume();
    }

    @Test
    void resumeIfRedisHealthy_resumesPausedContainerWhenRedisResponds() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer("orderCacheEvictionConsumer")).thenReturn(container);
        when(container.isPauseRequested()).thenReturn(true);

        scheduler.resumeIfRedisHealthy();

        verify(redisTemplate).hasKey("__health__");
        verify(container).resume();
    }

    @Test
    void resumeIfRedisHealthy_keepsContainerPausedOnRedisConnectionFailure() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer("orderCacheEvictionConsumer")).thenReturn(container);
        when(container.isPauseRequested()).thenReturn(true);
        when(redisTemplate.hasKey("__health__")).thenThrow(new RedisConnectionFailureException("down"));

        scheduler.resumeIfRedisHealthy();

        verify(container, never()).resume();
    }

    @Test
    void resumeIfRedisHealthy_keepsContainerPausedOnRedisTimeout() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer("orderCacheEvictionConsumer")).thenReturn(container);
        when(container.isPauseRequested()).thenReturn(true);
        when(redisTemplate.hasKey("__health__")).thenThrow(new QueryTimeoutException("timeout"));

        scheduler.resumeIfRedisHealthy();

        verify(container, never()).resume();
    }
}
