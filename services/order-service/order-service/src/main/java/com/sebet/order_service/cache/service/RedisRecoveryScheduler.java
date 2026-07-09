package com.sebet.order_service.cache.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RedisRecoveryScheduler {

    private static final String CONTAINER_ID = "orderCacheEvictionConsumer";

    private final KafkaListenerEndpointRegistry registry;
    private final StringRedisTemplate redisTemplate;

    public RedisRecoveryScheduler(
            KafkaListenerEndpointRegistry registry,
            @Qualifier("healthCheckRedisTemplate") StringRedisTemplate redisTemplate
    ) {
        this.registry = registry;
        this.redisTemplate = redisTemplate;
    }

    @Scheduled(fixedDelay = 10_000)
    public void resumeIfRedisHealthy() {
        MessageListenerContainer container = registry.getListenerContainer(CONTAINER_ID);
        if (container == null || !container.isPauseRequested()) {
            return;
        }
        try {
            redisTemplate.hasKey("__health__");
            log.info("Redis recovered; resuming cache eviction consumer");
            container.resume();
        } catch (RedisConnectionFailureException | QueryTimeoutException e) {
            log.debug("Redis still unavailable; cache eviction consumer remains paused");
        }
    }
}
