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

    /**
     * Cache 8 + Cache 4 + Cache 6 — atomic propose-changes hot-view update.
     *
     * Atomically writes the new proposal to C8, updates the order status in C4,
     * and appends the new timeline entry to C6.
     *
     * KEYS[1] = order:proposals:{orderId}   (C8)
     * KEYS[2] = order:status:{orderId}      (C4)
     * KEYS[3] = order:timeline:{orderId}    (C6)
     * ARGV[1] = proposal JSON (C8 value)
     * ARGV[2] = C8 TTL seconds
     * ARGV[3] = status value (C4 value: "STATUS|userId|storeId")
     * ARGV[4] = C4 TTL seconds
     * ARGV[5] = timeline entry JSON (C6 value to append)
     * ARGV[6] = C6 TTL seconds
     * Returns: 1 always.
     */
    @Bean
    public RedisScript<Long> proposeChangesRedisUpdateScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
                "redis.call('set', KEYS[1], ARGV[1], 'ex', tonumber(ARGV[2])) " +
                "redis.call('set', KEYS[2], ARGV[3], 'ex', tonumber(ARGV[4])) " +
                "redis.call('rpush', KEYS[3], ARGV[5]) " +
                "redis.call('expire', KEYS[3], tonumber(ARGV[6])) " +
                "return 1"
        );
        script.setResultType(Long.class);
        return script;
    }

    /**
     * Cache 8 + Cache 4 + Cache 6 -- atomic active-proposal cancellation.
     *
     * Deletes the active proposal, updates the current status back to CONFIRMED,
     * and removes active proposal-wait timeline entries from C6.
     *
     * KEYS[1] = order:proposals:{orderId}   (C8)
     * KEYS[2] = order:status:{orderId}      (C4)
     * KEYS[3] = order:timeline:{orderId}    (C6)
     * ARGV[1] = status value (C4 value: "CONFIRMED|userId|storeId")
     * ARGV[2] = C4 TTL seconds
     * ARGV[3] = C6 TTL seconds
     * ARGV[4] = timeline status to remove ("AWAITING_CUSTOMER_RESPONSE")
     * Returns: 1 always.
     */
    @Bean
    public RedisScript<Long> cancelActiveProposalRedisUpdateScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
                "redis.call('del', KEYS[1]) " +
                "redis.call('set', KEYS[2], ARGV[1], 'ex', tonumber(ARGV[2])) " +
                "local entries = redis.call('lrange', KEYS[3], 0, -1) " +
                "redis.call('del', KEYS[3]) " +
                "for _, entry in ipairs(entries) do " +
                "  local keep = true " +
                "  local ok, decoded = pcall(cjson.decode, entry) " +
                "  if ok and decoded['status'] == ARGV[4] then " +
                "    keep = false " +
                "  end " +
                "  if keep then " +
                "    redis.call('rpush', KEYS[3], entry) " +
                "  end " +
                "end " +
                "if redis.call('llen', KEYS[3]) > 0 then " +
                "  redis.call('expire', KEYS[3], tonumber(ARGV[3])) " +
                "end " +
                "return 1"
        );
        script.setResultType(Long.class);
        return script;
    }

    /**
     * Cache 1c + Cache 1 + Cache 1b + Cache 4 -- atomic scheduled activation.
     *
     * Moves a scheduled order into the active order sets and updates the status
     * hot-view in one Redis operation.
     *
     * KEYS[1] = store:scheduled_orders:{storeId} (C1c)
     * KEYS[2] = user:active_orders:{userId}      (C1)
     * KEYS[3] = store:active_orders:{storeId}    (C1b)
     * KEYS[4] = order:status:{orderId}           (C4)
     * ARGV[1] = orderId
     * ARGV[2] = status value (C4 value: "PENDING|userId|storeId")
     * ARGV[3] = C1b TTL seconds
     * ARGV[4] = C4 TTL seconds
     * Returns: 1 always.
     */
    @Bean
    public RedisScript<Long> activateScheduledOrderRedisUpdateScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
                "redis.call('zrem', KEYS[1], ARGV[1]) " +
                "redis.call('sadd', KEYS[2], ARGV[1]) " +
                "redis.call('sadd', KEYS[3], ARGV[1]) " +
                "redis.call('expire', KEYS[3], tonumber(ARGV[3])) " +
                "redis.call('set', KEYS[4], ARGV[2], 'ex', tonumber(ARGV[4])) " +
                "return 1"
        );
        script.setResultType(Long.class);
        return script;
    }

    /**
     * Cache 8 — atomic proposal cache deletion after the customer responds to a proposal.
     *
     * Called after ACCEPT_ALL or ACCEPT_WITH_MODIFICATIONS. The order stays in
     * AWAITING_CUSTOMER_RESPONSE; only the proposals key needs to be removed since
     * the promo service will call back with the recalculated order.
     *
     * KEYS[1] = order:proposals:{orderId}  (C8)
     * Returns: 1 always.
     */
    @Bean
    public RedisScript<Long> respondAcceptRedisUpdateScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
                "redis.call('del', KEYS[1]) " +
                "return 1"
        );
        script.setResultType(Long.class);
        return script;
    }

    /**
     * Cache 1c + Cache 2 — atomic scheduled-order update.
     *
     * Conditionally updates the Cache 1c ZSET score when the customer reschedules
     * a delivery window, and conditionally updates the Cache 2 snapshot in-place
     * when address or phone number changes.  Both operations are skipped when the
     * corresponding ARGV sentinel is an empty string.
     *
     * Cache 2 is only written when the key already exists (KEEPTTL preserves the
     * remaining TTL).  If the snapshot was already evicted there is no stale entry
     * to worry about — the next read will rebuild from the DB.
     *
     * Requires Redis 6.0+ for the KEEPTTL option on SET.
     *
     * KEYS[1] = store:scheduled_orders:{storeId}  (C1c — ZSET)
     * KEYS[2] = order:{orderId}                   (C2  — STRING)
     * ARGV[1] = orderId (ZSET member)
     * ARGV[2] = new epoch-millis score, or "" to skip the ZSET update
     * ARGV[3] = full updated Cache 2 JSON, or "" to skip the snapshot update
     * Returns: 1 always.
     */
    @Bean
    public RedisScript<Long> updateScheduledOrderRedisScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
                "if ARGV[2] ~= '' then " +
                "  redis.call('zrem', KEYS[1], ARGV[1]) " +
                "  redis.call('zadd', KEYS[1], tonumber(ARGV[2]), ARGV[1]) " +
                "end " +
                "if ARGV[3] ~= '' then " +
                "  if redis.call('exists', KEYS[2]) == 1 then " +
                "    redis.call('set', KEYS[2], ARGV[3], 'keepttl') " +
                "  end " +
                "end " +
                "return 1"
        );
        script.setResultType(Long.class);
        return script;
    }

    /**
     * Atomic cancellation hot-view cleanup.
     *
     * KEYS[1] = user:active_orders:{userId}
     * KEYS[2] = store:active_orders:{storeId}
     * KEYS[3] = order:{orderId}
     * KEYS[4] = order:tracking:{orderId}
     * KEYS[5] = order:status:{orderId}
     * KEYS[6] = order:timeline:{orderId}
     * KEYS[7] = order:proposals:{orderId}
     * ARGV[1] = orderId
     * Returns: 1 always.
     */
    @Bean
    public RedisScript<Long> evictCancelledOrderHotViewsScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
                "redis.call('srem', KEYS[1], ARGV[1]) " +
                "if redis.call('scard', KEYS[1]) == 0 then " +
                "  redis.call('del', KEYS[1]) " +
                "end " +
                "redis.call('srem', KEYS[2], ARGV[1]) " +
                "if redis.call('scard', KEYS[2]) == 0 then " +
                "  redis.call('del', KEYS[2]) " +
                "end " +
                "redis.call('del', KEYS[3], KEYS[4], KEYS[5], KEYS[6], KEYS[7]) " +
                "return 1"
        );
        script.setResultType(Long.class);
        return script;
    }
}
