package com.sebet.cartservice.cart.repository;

import com.sebet.cartservice.cart.metrics.CartMetrics;
import com.sebet.cartservice.cart.migration.CartSchemaMigrationService;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class CartRedisRepository {

    private static final String CART_KEY_PREFIX    = "cart:";
    private static final String VERSION_KEY_SUFFIX = ":v";
    private static final Duration CART_TTL         = Duration.ofDays(7);

    // Lua CAS script:
    //   KEYS[1] = cart:{userId}       (cart JSON)
    //   KEYS[2] = cart:{userId}:v     (version integer)
    //   ARGV[1] = expectedVersion     (string, e.g. "3")
    //   ARGV[2] = serialised cart     (JSON bytes)
    //   ARGV[3] = newVersion          (string, e.g. "4")
    //   ARGV[4] = TTL seconds         (string)
    //
    // nil from GET is treated as "0" so brand-new carts (no version key yet) always match.
    private static final byte[] CAS_SCRIPT = (
            "local current = redis.call('get', KEYS[2]) " +
            "if current == false then current = '0' end " +
            "if current == ARGV[1] then " +
            "  redis.call('setex', KEYS[1], tonumber(ARGV[4]), ARGV[2]) " +
            "  redis.call('setex', KEYS[2], tonumber(ARGV[4]), ARGV[3]) " +
            "  return 1 " +
            "else " +
            "  return 0 " +
            "end"
    ).getBytes(StandardCharsets.UTF_8);

    private final RedisTemplate<String, RedisCart> redisTemplate;
    private final CartSchemaMigrationService cartSchemaMigrationService;
    private final CartMetrics cartMetrics;

    // ── Read ──────────────────────────────────────────────────────────────────

    public Optional<RedisCart> findByUserId(String userId) {
        String key = buildCartKey(userId);

        RedisCart cart;
        try {
            cart = redisTemplate.opsForValue().get(key);
        } catch (Exception ex) {
            log.error("Failed to deserialize cart for userId={}, discarding. reason={}", userId, ex.getMessage());
            cartMetrics.recordSchemaDeserializationFailed();
            redisTemplate.delete(List.of(buildCartKey(userId), buildVersionKey(userId)));
            return Optional.empty();
        }

        if (cart == null) {
            return Optional.empty();
        }

        if (cart.getSchemaVersion() < RedisCart.CURRENT_SCHEMA_VERSION) {
            int fromVersion = cart.getSchemaVersion();
            int toVersion   = RedisCart.CURRENT_SCHEMA_VERSION;
            log.warn("Cart schema version is outdated for userId={}, expected={}, found={}, attempting migration.",
                    userId, toVersion, fromVersion);
            cartMetrics.recordSchemaMigrationAttempted(fromVersion, toVersion);
            long versionBeforeMigration = cart.getVersion();
            Timer.Sample migrationTimer = cartMetrics.startSchemaMigrationTimer();
            try {
                cart = cartSchemaMigrationService.migrate(cart);
                if (!saveIfVersionMatches(userId, cart, versionBeforeMigration)) {
                    log.error("Cart migration save conflict for userId={}, returning empty to preserve original data.",
                            userId);
                    cartMetrics.recordSchemaMigrationCasConflict(fromVersion, toVersion);
                    return Optional.empty();
                }
                cartMetrics.recordSchemaMigrationSucceeded(fromVersion, toVersion);
            } catch (Exception ex) {
                log.error("Cart migration failed for userId={}, returning empty to preserve original data in Redis. reason={}",
                        userId, ex.getMessage());
                cartMetrics.recordSchemaMigrationFailed(fromVersion, toVersion);
                return Optional.empty();
            } finally {
                cartMetrics.stopSchemaMigrationTimer(migrationTimer, fromVersion, toVersion);
            }
        }

        if (cart.getSchemaVersion() > RedisCart.CURRENT_SCHEMA_VERSION) {
            log.warn("Cart schema version is newer than expected for userId={}, expected={}, found={}. " +
                            "Skipping to avoid data loss during rolling deployment.",
                    userId, RedisCart.CURRENT_SCHEMA_VERSION, cart.getSchemaVersion());
            cartMetrics.recordSchemaVersionTooNew(cart.getSchemaVersion());
            return Optional.empty();
        }

        return Optional.of(cart);
    }

    // ── Write — optimistic CAS ────────────────────────────────────────────────

    /**
     * Atomically saves {@code cart} only when the current version key in Redis
     * equals {@code expectedVersion}.
     *
     * <p>Increments {@code cart.version} in-place before serialising so the stored
     * JSON and the version key are always in sync.
     *
     * @param expectedVersion the version read by the caller before any mutations
     * @return {@code true} if saved; {@code false} if a concurrent write was detected
     */
    public boolean saveIfVersionMatches(String userId, RedisCart cart, long expectedVersion) {
        cart.incrementVersion();

        byte[] cartKeyBytes    = serializeKey(buildCartKey(userId));
        byte[] versionKeyBytes = serializeKey(buildVersionKey(userId));
        byte[] expectedBytes   = String.valueOf(expectedVersion).getBytes(StandardCharsets.UTF_8);
        byte[] cartBytes       = serializeCart(cart);
        byte[] newVersionBytes = String.valueOf(cart.getVersion()).getBytes(StandardCharsets.UTF_8);
        byte[] ttlBytes        = String.valueOf(CART_TTL.getSeconds()).getBytes(StandardCharsets.UTF_8);

        Long result = redisTemplate.execute((RedisCallback<Long>) connection ->
                connection.eval(
                        CAS_SCRIPT,
                        ReturnType.INTEGER,
                        2,
                        cartKeyBytes, versionKeyBytes,
                        expectedBytes, cartBytes, newVersionBytes, ttlBytes
                )
        );

        return Long.valueOf(1L).equals(result);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public void deleteByUserId(String userId) {
        redisTemplate.delete(List.of(buildCartKey(userId), buildVersionKey(userId)));
    }

    // ── Existence check ───────────────────────────────────────────────────────

    public boolean existsByUserId(String userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(buildCartKey(userId)));
    }

    // ── Key helpers ───────────────────────────────────────────────────────────

    private String buildCartKey(String userId) {
        return CART_KEY_PREFIX + userId;
    }

    private String buildVersionKey(String userId) {
        return CART_KEY_PREFIX + userId + VERSION_KEY_SUFFIX;
    }

    // ── Serialisation helpers ─────────────────────────────────────────────────

    private byte[] serializeKey(String key) {
        return Objects.requireNonNull(
                ((RedisSerializer<String>) redisTemplate.getKeySerializer()).serialize(key),
                "Key serialisation returned null for: " + key
        );
    }

    @SuppressWarnings("unchecked")
    private byte[] serializeCart(RedisCart cart) {
        return Objects.requireNonNull(
                ((RedisSerializer<RedisCart>) redisTemplate.getValueSerializer()).serialize(cart),
                "Cart serialisation returned null for cartId=" + cart.getCartId()
        );
    }
}
