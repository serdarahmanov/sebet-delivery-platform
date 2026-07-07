package com.sebet.order_service.cache.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoreMembershipRedisRepositoryTest {

    private static final Duration TTL = Duration.ofHours(48);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final RedisScript<Long> removeActiveOrderScript = mock(RedisScript.class);

    @SuppressWarnings("unchecked")
    private final SetOperations<String, String> setOperations = mock(SetOperations.class);

    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);

    @Test
    void storeActiveAddRefreshesTtl() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        StoreActiveOrdersRedisRepository repository =
                new StoreActiveOrdersRedisRepository(redisTemplate, removeActiveOrderScript);

        repository.add("store-1", "order-1");

        verify(setOperations).add("store:active_orders:store-1", "order-1");
        verify(redisTemplate).expire("store:active_orders:store-1", TTL);
    }

    @Test
    void storeScheduledAddRefreshesTtl() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        StoreScheduledOrdersRedisRepository repository =
                new StoreScheduledOrdersRedisRepository(redisTemplate);
        Instant scheduledFor = Instant.parse("2026-07-07T10:00:00Z");

        repository.add("store-1", "order-1", scheduledFor);

        verify(zSetOperations).add(
                "store:scheduled_orders:store-1",
                "order-1",
                (double) scheduledFor.toEpochMilli()
        );
        verify(redisTemplate).expire("store:scheduled_orders:store-1", TTL);
    }
}
