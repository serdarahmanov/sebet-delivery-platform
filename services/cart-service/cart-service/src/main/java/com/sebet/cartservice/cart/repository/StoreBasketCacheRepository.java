package com.sebet.cartservice.cart.repository;

import com.sebet.cartservice.cart.model.CartDeliveryQuote;
import com.sebet.cartservice.cart.model.StoreBasket;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class StoreBasketCacheRepository {

    private static final String KEY_PREFIX = "store-basket:";
    private static final Duration FALLBACK_TTL = Duration.ofMinutes(5);
    private static final Duration QUOTE_EXPIRY_BUFFER = Duration.ofSeconds(10);

    private final RedisTemplate<String, StoreBasket> storeBasketRedisTemplate;

    public Optional<StoreBasket> find(String userId, String storeId) {
        return Optional.ofNullable(storeBasketRedisTemplate.opsForValue().get(buildKey(userId, storeId)));
    }

    public void save(String userId, String storeId, StoreBasket basket) {
        if (basket == null) return;
        storeBasketRedisTemplate.opsForValue().set(buildKey(userId, storeId), basket, computeTtl(basket));
    }

    public void evict(String userId, String storeId) {
        storeBasketRedisTemplate.delete(buildKey(userId, storeId));
    }

    private String buildKey(String userId, String storeId) {
        return KEY_PREFIX + userId + ":" + storeId;
    }

    /**
     * Aligns the cache TTL to the delivery quote expiry, minus a 10-second buffer.
     * This ensures the cache is evicted and rebuilt with a fresh quote before the
     * quote expires on the delivery service's side — the user never sees a stale fee.
     *
     * Falls back to 5 minutes when there is no quote (nothing time-sensitive to align to).
     */
    private Duration computeTtl(StoreBasket basket) {
        CartDeliveryQuote quote = basket.deliveryQuote();
        if (quote != null && quote.expiresAt() != null) {
            Duration remaining = Duration.between(Instant.now(), quote.expiresAt())
                    .minus(QUOTE_EXPIRY_BUFFER);
            // Quote already expired or expires within the buffer — don't cache
            if (remaining.isNegative() || remaining.isZero()) {
                return Duration.ofSeconds(1);
            }
            return remaining;
        }
        // No quote → nothing time-sensitive; use the long fallback TTL
        return FALLBACK_TTL;
    }
}
