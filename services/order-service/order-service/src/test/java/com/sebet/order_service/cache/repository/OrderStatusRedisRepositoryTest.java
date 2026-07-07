package com.sebet.order_service.cache.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderStatusRedisRepositoryTest {

    private static final Duration TTL = Duration.ofHours(48);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private final OrderStatusRedisRepository repository = new OrderStatusRedisRepository(redisTemplate);

    @Test
    void saveStoresStatusCustomerAndStoreOwnership() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        repository.save("order-1", "customer-1", "store-1", "CONFIRMED");

        verify(valueOperations).set("order:status:order-1", "CONFIRMED|customer-1|store-1", TTL);
    }

    @Test
    void findByIdParsesStatusCustomerAndStoreOwnership() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("order:status:order-1")).thenReturn("READY_FOR_PICKUP|customer-1|store-1");

        assertThat(repository.findById("order-1"))
                .contains(new OrderStatusRedisRepository.Entry("READY_FOR_PICKUP", "customer-1", "store-1"));
    }

    @Test
    void findByIdTreatsOldTwoPartFormatAsCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("order:status:order-1")).thenReturn("CONFIRMED|customer-1");

        assertThat(repository.findById("order-1")).isEmpty();
    }
}
