package com.sebet.order_service.cache.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis Lua scripts registered as beans so Spring Data Redis can cache their
 * SHA digests and avoid re-sending the script body on every call (EVALSHA).
 */
@Configuration
public class OrderRedisConfig {

    /**
     * Cache 5 — atomic lock release.
     *
     * Only deletes the lock key if the stored value equals the caller's
     * instance ID.  Prevents one instance from accidentally releasing a lock
     * that expired and was re-acquired by a different instance.
     *
     * KEYS[1] = order:lock:{cartId}
     * ARGV[1] = instanceId
     * Returns: 1 if released, 0 if the lock belongs to someone else.
     */
    @Bean
    public RedisScript<Long> releaseLockScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "  return redis.call('del', KEYS[1]) " +
                "else " +
                "  return 0 " +
                "end"
        );
        script.setResultType(Long.class);
        return script;
    }

    /**
     * Cache 1 — atomic active-order removal with empty-set cleanup.
     *
     * Removes the orderId from the SET, then deletes the entire key when the
     * set is now empty.  Without this atomic step there is a window where a
     * new SADD could race between the SREM and a subsequent DEL, silently
     * wiping the new order from the active set.
     *
     * KEYS[1] = user:active_orders:{userId}
     * ARGV[1] = orderId
     * Returns: 1 always.
     */
    @Bean
    public RedisScript<Long> removeActiveOrderScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
                "redis.call('srem', KEYS[1], ARGV[1]) " +
                "if redis.call('scard', KEYS[1]) == 0 then " +
                "  redis.call('del', KEYS[1]) " +
                "end " +
                "return 1"
        );
        script.setResultType(Long.class);
        return script;
    }
}
