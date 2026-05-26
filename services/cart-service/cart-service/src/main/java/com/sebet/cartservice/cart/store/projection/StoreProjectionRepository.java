package com.sebet.cartservice.cart.store.projection;

import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface StoreProjectionRepository extends JpaRepository<StoreProjection, String> {

    // Cart validation bulk lookup — single IN clause, typically few stores → 1 s cap.
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "1000"))
    List<StoreProjection> findByStoreIdIn(Collection<String> storeIds);
}
