package com.sebet.cartservice.cart.config;

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
    public RedisTemplate<String, RedisCart> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, RedisCart> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);

        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();
        JacksonJsonRedisSerializer<RedisCart> cartSerializer = new JacksonJsonRedisSerializer<>(RedisCart.class);

        template.setKeySerializer(stringRedisSerializer);
        template.setValueSerializer(cartSerializer);


       template.afterPropertiesSet();
        return template;
    }
}
