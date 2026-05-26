package com.sebet.cartservice.cart.validation.tools;

import com.sebet.cartservice.cart.inventory.projection.InventoryProjection;
import com.sebet.cartservice.cart.product.projection.ProductProjection;
import com.sebet.cartservice.cart.store.projection.StoreProjection;

import java.util.Map;

public record CartValidationLookupContext(
        Map<ProductStoreKey, ProductProjection> productByProductStore,
        Map<ProductStoreKey, InventoryProjection> inventoryByProductStore,
        Map<String, StoreProjection> storeByStoreId
) {
    public record ProductStoreKey(String productId, String storeId) {
    }
}
