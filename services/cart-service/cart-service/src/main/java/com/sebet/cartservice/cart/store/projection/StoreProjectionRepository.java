package com.sebet.cartservice.cart.store.projection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StoreProjectionRepository extends JpaRepository<StoreProjection, String> {
}
