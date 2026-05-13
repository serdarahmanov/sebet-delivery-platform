package com.sebet.cartservice.cart.product.projection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductProjectionRepository extends JpaRepository<ProductProjection, Long> {

    Optional<ProductProjection> findByProductIdAndStoreId(String productId, String storeId);

    List<ProductProjection> findByProductIdIn(Collection<String> productIds);

    List<ProductProjection> findByStoreId(String storeId);

    List<ProductProjection> findByProductIdInAndStoreIdIn(
            Collection<String> productIds,
            Collection<String> storeIds
    );

    boolean existsByProductIdAndStoreId(String productId, String storeId);
}
