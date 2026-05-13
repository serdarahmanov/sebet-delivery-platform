package com.sebet.cartservice.cart.repository;


import com.sebet.cartservice.cart.model.redis.RedisCart;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class CartRedisRepository {

    private static final String CART_KEY_PREFIX = "cart:";
   private static final Duration CART_TTL = Duration.ofDays(7);
    private final RedisTemplate<String, RedisCart> redisTemplate;


   public Optional<RedisCart> findByUserId(String userId) {
       String key = buildCartKey(userId);

       RedisCart cart = redisTemplate.opsForValue().get(key);

       return Optional.ofNullable(cart);
   };



   public RedisCart save(String userId, RedisCart cart) {
       String key = buildCartKey(userId);
       redisTemplate.opsForValue().set(key, cart,CART_TTL);
       return cart;
   }

    public void deleteByUserId(String userId) {
        String key = buildCartKey(userId);

        redisTemplate.delete(key);
    }

    public boolean existsByUserId(String userId) {
        String key = buildCartKey(userId);

        Boolean exists = redisTemplate.hasKey(key);

        return Boolean.TRUE.equals(exists);
    }




    private String buildCartKey(String userId) {
        return CART_KEY_PREFIX + userId;
    }
}
