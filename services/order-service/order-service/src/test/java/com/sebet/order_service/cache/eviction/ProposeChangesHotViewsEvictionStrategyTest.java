package com.sebet.order_service.cache.eviction;

import com.sebet.order_service.cache.keys.RedisKeys;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProposeChangesHotViewsEvictionStrategyTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ProposeChangesHotViewsEvictionStrategy strategy =
            new ProposeChangesHotViewsEvictionStrategy(redisTemplate);

    @Test
    void cacheName_returnsProposeChangesHotViews() {
        assertThat(strategy.cacheName()).isEqualTo("PROPOSE_CHANGES_HOT_VIEWS");
    }

    @Test
    void cacheKey_returnsOrderProposalsKey() {
        String orderId = UUID.randomUUID().toString();
        assertThat(strategy.cacheKey(orderId)).isEqualTo(RedisKeys.orderProposals(orderId));
    }

    @Test
    void cacheKeys_returnsC8C4C6InOrder() {
        String orderId = UUID.randomUUID().toString();
        assertThat(strategy.cacheKeys(orderId)).containsExactly(
                RedisKeys.orderProposals(orderId),
                RedisKeys.orderStatus(orderId),
                RedisKeys.orderTimeline(orderId)
        );
    }

    @Test
    void evict_deletesAllThreeKeysAtomically() {
        String orderId = UUID.randomUUID().toString();

        strategy.evict(orderId);

        verify(redisTemplate).delete(List.of(
                RedisKeys.orderProposals(orderId),
                RedisKeys.orderStatus(orderId),
                RedisKeys.orderTimeline(orderId)
        ));
    }
}
