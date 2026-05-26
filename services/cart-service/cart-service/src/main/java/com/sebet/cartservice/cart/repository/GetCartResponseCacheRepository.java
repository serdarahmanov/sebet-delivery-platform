package com.sebet.cartservice.cart.repository;

import com.sebet.cartservice.cart.dto.getcart.CartSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class GetCartResponseCacheRepository {
    private static final String KEY_PREFIX = "cart-response:";
    // Aligned with delivery quote TTL (~120 s) so the cached response never
    // outlives the fee data embedded in it. Mutations evict this cache immediately.
    private static final Duration TTL = Duration.ofSeconds(120);

    private final RedisTemplate<String, CartSummaryResponse> cartSummaryResponseRedisTemplate;

    public Optional<CartSummaryResponse> findByUserId(String userId) {
        return Optional.ofNullable(cartSummaryResponseRedisTemplate.opsForValue().get(buildKey(userId)));
    }

    public boolean existsByUserId(String userId) {
        Boolean exists = cartSummaryResponseRedisTemplate.hasKey(buildKey(userId));
        return Boolean.TRUE.equals(exists);
    }

    public void save(String userId, CartSummaryResponse response) {
        if (response == null) {
            return;
        }
        cartSummaryResponseRedisTemplate.opsForValue().set(buildKey(userId), response, TTL);
    }

    public void evict(String userId) {
        cartSummaryResponseRedisTemplate.delete(buildKey(userId));
    }

    private String buildKey(String userId) {
        return KEY_PREFIX + userId;
    }
}
