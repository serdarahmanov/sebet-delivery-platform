package com.sebet.cartservice.cart.config;

import tools.jackson.databind.ObjectMapper;
import com.sebet.cartservice.cart.dto.getcart.CartSummaryResponse;
import com.sebet.cartservice.cart.model.StoreBasket;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, RedisCart> redisTemplate(
            RedisConnectionFactory redisConnectionFactory,
            ObjectMapper objectMapper
    ) {
        RedisTemplate<String, RedisCart> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);

        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();
        JacksonJsonRedisSerializer<RedisCart> cartSerializer =
                new JacksonJsonRedisSerializer<RedisCart>(objectMapper, RedisCart.class);

        template.setKeySerializer(stringRedisSerializer);
        template.setValueSerializer(cartSerializer);

        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisTemplate<String, CartSummaryResponse> cartSummaryResponseRedisTemplate(
            RedisConnectionFactory redisConnectionFactory,
            ObjectMapper objectMapper
    ) {
        RedisTemplate<String, CartSummaryResponse> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);

        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();
        JacksonJsonRedisSerializer<CartSummaryResponse> responseSerializer =
                new JacksonJsonRedisSerializer<CartSummaryResponse>(objectMapper, CartSummaryResponse.class);

        template.setKeySerializer(stringRedisSerializer);
        template.setValueSerializer(responseSerializer);

        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisTemplate<String, StoreBasket> storeBasketRedisTemplate(
            RedisConnectionFactory redisConnectionFactory,
            ObjectMapper objectMapper
    ) {
        RedisTemplate<String, StoreBasket> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);

        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();
        JacksonJsonRedisSerializer<StoreBasket> serializer =
                new JacksonJsonRedisSerializer<StoreBasket>(objectMapper, StoreBasket.class);

        template.setKeySerializer(stringRedisSerializer);
        template.setValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }

}
