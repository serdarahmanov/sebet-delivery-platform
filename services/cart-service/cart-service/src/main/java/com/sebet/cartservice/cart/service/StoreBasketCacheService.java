package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.model.StoreBasket;
import com.sebet.cartservice.cart.repository.StoreBasketCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoreBasketCacheService {

    private final StoreBasketCacheRepository repository;

    public Optional<StoreBasket> getIfPresent(String userId, String storeId) {
        return repository.find(userId, storeId);
    }

    public void cache(String userId, String storeId, StoreBasket basket) {
        repository.save(userId, storeId, basket);
        log.debug("store_basket_cached userId={} storeId={}", userId, storeId);
    }

    public void evict(String userId, String storeId) {
        repository.evict(userId, storeId);
        log.debug("store_basket_evicted userId={} storeId={}", userId, storeId);
    }
}
