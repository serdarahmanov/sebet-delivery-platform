package com.sebet.cartservice.cart.product.projection;

import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductProjectionRepository extends JpaRepository<ProductProjection, Long> {

    Optional<ProductProjection> findByProductIdAndStoreId(String productId, String storeId);

    List<ProductProjection> findByProductIdIn(Collection<String> productIds);

    List<ProductProjection> findByStoreId(String storeId);

    // Cart validation bulk lookup — two IN clauses, largest potential result set → 2 s cap.
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    List<ProductProjection> findByProductIdInAndStoreIdIn(
            Collection<String> productIds,
            Collection<String> storeIds
    );

    boolean existsByProductIdAndStoreId(String productId, String storeId);
}
