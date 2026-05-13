package com.sebet.cartservice.cart.inventory.projection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InventoryProjectionRepository extends JpaRepository<InventoryProjection, Long> {

    Optional<InventoryProjection> findByProductIdAndStoreId(
            String productId,
            String storeId
    );

    List<InventoryProjection> findByProductIdInAndStoreIdIn(
            Collection<String> productIds,
            Collection<String> storeIds
    );
}
