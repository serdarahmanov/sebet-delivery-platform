package com.sebet.cartservice.cart.inventory.projection;

import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InventoryProjectionRepository extends JpaRepository<InventoryProjection, Long> {

    Optional<InventoryProjection> findByProductIdAndStoreId(
            String productId,
            String storeId
    );

    // Cart validation bulk lookup — two IN clauses, simpler row structure → 1.5 s cap.
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "1500"))
    List<InventoryProjection> findByProductIdInAndStoreIdIn(
            Collection<String> productIds,
            Collection<String> storeIds
    );
}
