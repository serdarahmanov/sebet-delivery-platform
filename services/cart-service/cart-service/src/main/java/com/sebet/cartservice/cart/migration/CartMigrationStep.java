package com.sebet.cartservice.cart.migration;

import com.sebet.cartservice.cart.model.redis.RedisCart;

/**
 * Defines a single incremental schema migration for {@link RedisCart}.
 *
 * <p>Each implementation handles exactly one version hop (N → N+1). Spring
 * auto-discovers all {@code @Component} implementations and registers them
 * in {@link CartSchemaMigrationService}.
 *
 * <p><b>Adding a new version:</b> bump {@code RedisCart.CURRENT_SCHEMA_VERSION}
 * and create a new {@code CartMigrationStepVNToVN1} class — nothing else needs
 * to change.
 */
public interface CartMigrationStep {

    /**
     * The schema version this step migrates <em>from</em>.
     * The step is expected to produce a cart whose {@code schemaVersion} is
     * {@code fromVersion() + 1}.
     */
    int fromVersion();

    /**
     * Applies the migration and returns the upgraded cart.
     * Implementations must set {@code cart.schemaVersion = fromVersion() + 1}
     * before returning.
     *
     * @param cart the cart at {@link #fromVersion()} — may be mutated in-place
     * @return the migrated cart (may be the same instance)
     */
    RedisCart migrate(RedisCart cart);
}
