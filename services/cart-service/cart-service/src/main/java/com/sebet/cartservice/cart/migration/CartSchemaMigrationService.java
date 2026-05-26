package com.sebet.cartservice.cart.migration;

import com.sebet.cartservice.cart.model.redis.RedisCart;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Walks the registered {@link CartMigrationStep} chain to upgrade a
 * {@link RedisCart} from any older schema version to
 * {@link RedisCart#CURRENT_SCHEMA_VERSION}.
 *
 * <p>Steps are discovered via Spring DI — every {@code @Component} that
 * implements {@link CartMigrationStep} is automatically registered. The chain
 * is walked one hop at a time (v1→v2→v3…), so steps are always applied in
 * order regardless of how far behind the stored cart is.
 *
 * <p><b>Thread safety:</b> the step map is built once at construction time and
 * is immutable thereafter; this service is safe to use as a singleton.
 */
@Slf4j
@Service
public class CartSchemaMigrationService {

    private final Map<Integer, CartMigrationStep> stepsByFromVersion;

    public CartSchemaMigrationService(List<CartMigrationStep> steps) {
        this.stepsByFromVersion = steps.stream()
                .collect(Collectors.toMap(CartMigrationStep::fromVersion, Function.identity()));

        log.info("CartSchemaMigrationService initialized with {} migration step(s): {}",
                steps.size(),
                steps.stream()
                        .map(s -> "v" + s.fromVersion() + "→v" + (s.fromVersion() + 1))
                        .collect(Collectors.joining(", ")));
    }

    /**
     * Migrates {@code cart} to {@link RedisCart#CURRENT_SCHEMA_VERSION} by
     * walking the migration chain step by step.
     *
     * @param cart the cart to migrate — its {@code schemaVersion} must be less
     *             than {@code CURRENT_SCHEMA_VERSION}
     * @return the fully migrated cart (may be the same instance as the input)
     * @throws CartMigrationException if a step is missing for any version in
     *                                the chain, making migration impossible
     */
    public RedisCart migrate(RedisCart cart) {
        int startVersion = cart.getSchemaVersion();

        while (cart.getSchemaVersion() < RedisCart.CURRENT_SCHEMA_VERSION) {
            int currentVersion = cart.getSchemaVersion();
            CartMigrationStep step = stepsByFromVersion.get(currentVersion);

            if (step == null) {
                throw new CartMigrationException(
                        "No migration step registered for schema version " + currentVersion +
                        ". Cannot migrate cart userId=" + cart.getUserId() +
                        " from v" + startVersion + " to v" + RedisCart.CURRENT_SCHEMA_VERSION + ".");
            }

            log.debug("Migrating cart userId={} schema v{} → v{}",
                    cart.getUserId(), currentVersion, currentVersion + 1);

            cart = step.migrate(cart);
        }

        log.info("Cart migration complete for userId={}: v{} → v{}",
                cart.getUserId(), startVersion, RedisCart.CURRENT_SCHEMA_VERSION);

        return cart;
    }
}
