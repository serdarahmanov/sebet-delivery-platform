package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.dto.getcart.CartSummaryResponse;
import com.sebet.cartservice.cart.model.cart_calculation.CartCalculationResult;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationContext;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.repository.CartRedisRepository;
import com.sebet.cartservice.cart.repository.GetCartResponseCacheRepository;
import com.sebet.cartservice.cart.metrics.CartMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartResponseCacheService {
    private final GetCartResponseCacheRepository cacheRepository;
    private final CartRedisRepository cartRedisRepository;
    private final CartValidationService cartValidationService;
    private final CartCalculationService cartCalculationService;
    private final GetCartResponseBuilder getCartResponseBuilder;
    private final CartMetrics cartMetrics;

    public Optional<CartSummaryResponse> getCachedCartSummaryResponse(String userId) {
        return cacheRepository.findByUserId(userId);
    }

    public boolean hasCachedCartSummaryResponse(String userId) {
        return cacheRepository.existsByUserId(userId);
    }

    public CartSummaryResponse getOrBuildCartSummaryResponse(String userId) {
        Optional<CartSummaryResponse> cached = getCachedCartSummaryResponse(userId);
        if (cached.isPresent()) {
            log.debug("cache_hit userId={}", userId);
            cartMetrics.recordCacheHit();
            return cached.get();
        }
        log.debug("cache_miss userId={}", userId);
        cartMetrics.recordCacheMiss();
        return rebuildAndCacheCartSummaryResponse(userId);
    }

    public CartSummaryResponse buildCartSummaryResponse(String userId) {
        RedisCart cart = cartRedisRepository.findByUserId(userId).orElse(null);
        if (cart == null) {
            return new CartSummaryResponse(null, java.util.List.of(), 0, java.util.List.of(), null, null);
        }
        CartValidationContext validationContext = cartValidationService.validateReadOnly(cart);
        CartCalculationResult calculationResult = cartCalculationService.calculate(cart, validationContext.validationResult());
        return getCartResponseBuilder.build(cart, validationContext, calculationResult);
    }

    public CartSummaryResponse rebuildAndCacheCartSummaryResponse(String userId) {
        CartSummaryResponse response = buildCartSummaryResponse(userId);
        cacheCartSummaryResponse(userId, response);
        return response;
    }

    public void evict(String userId) {
        cacheRepository.evict(userId);
        log.debug("cart_summary_evicted userId={}", userId);
    }

    public void refreshCachedCartSummaryResponseIfPresent(String userId) {
        if (!hasCachedCartSummaryResponse(userId)) {
            return;
        }
        cacheCartSummaryResponse(userId, buildCartSummaryResponse(userId));
    }

    public void cacheCartSummaryResponse(String userId, CartSummaryResponse response) {
        cacheRepository.save(userId, response);
        log.debug("cache_refreshed userId={}", userId);
    }
}
