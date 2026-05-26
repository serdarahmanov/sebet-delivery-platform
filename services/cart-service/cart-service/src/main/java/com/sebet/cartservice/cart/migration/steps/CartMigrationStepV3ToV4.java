package com.sebet.cartservice.cart.migration.steps;

import com.sebet.cartservice.cart.migration.CartMigrationStep;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisStoreBasket;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/**
 * Migrates a {@link RedisCart} from schema version 3 to version 4.
 *
 * <p><b>What changed in v4:</b> cart items and promo codes were moved out of a
 * flat top-level list and into per-store {@link RedisStoreBasket} buckets.
 * Jackson deserializes the basket structure from whatever fields were present
 * in Redis; this step guards against any basket whose collection fields were
 * absent in the stored JSON (which would deserialize as {@code null}) by
 * replacing them with empty lists so the rest of the application never has to
 * null-check them.
 *
 * <p><b>Adding future steps:</b> create {@code CartMigrationStepV4ToV5} in
 * this package and annotate it with {@code @Component} — the service picks it
 * up automatically.
 */
@Component
public class CartMigrationStepV3ToV4 implements CartMigrationStep {

    @Override
    public int fromVersion() {
        return 3;
    }

    @Override
    public RedisCart migrate(RedisCart cart) {
        if (cart.getStoreBaskets() == null) {
            cart.setStoreBaskets(new ArrayList<>());
        } else {
            for (RedisStoreBasket basket : cart.getStoreBaskets()) {
                if (basket.getItems() == null) {
                    basket.setItems(new ArrayList<>());
                }
                if (basket.getPromoCodes() == null) {
                    basket.setPromoCodes(new ArrayList<>());
                }
            }
        }

        cart.setSchemaVersion(4);
        return cart;
    }
}
