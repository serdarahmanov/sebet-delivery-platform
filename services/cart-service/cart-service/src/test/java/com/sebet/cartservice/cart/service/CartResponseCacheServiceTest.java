package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.dto.getcart.CartSummaryResponse;
import com.sebet.cartservice.cart.metrics.CartMetrics;
import com.sebet.cartservice.cart.model.cart_calculation.CartCalculationResult;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationContext;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.repository.CartRedisRepository;
import com.sebet.cartservice.cart.repository.GetCartResponseCacheRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartResponseCacheServiceTest {
    @Mock
    private GetCartResponseCacheRepository cacheRepository;
    @Mock
    private CartRedisRepository cartRedisRepository;
    @Mock
    private CartValidationService cartValidationService;
    @Mock
    private CartCalculationService cartCalculationService;
    @Mock
    private GetCartResponseBuilder getCartResponseBuilder;
    @Mock
    private CartMetrics cartMetrics;
    @InjectMocks
    private CartResponseCacheService service;

    @Test
    void getOrBuildReturnsCachedImmediately() {
        CartSummaryResponse cached = new CartSummaryResponse("c1", List.of(), 0, List.of(), null, null);
        when(cacheRepository.findByUserId("u1")).thenReturn(Optional.of(cached));

        CartSummaryResponse response = service.getOrBuildCartSummaryResponse("u1");

        assertThat(response).isSameAs(cached);
        verify(cartRedisRepository, never()).findByUserId(any());
    }

    @Test
    void refreshIfPresentSkipsWhenCacheMissing() {
        when(cacheRepository.existsByUserId("u1")).thenReturn(false);

        service.refreshCachedCartSummaryResponseIfPresent("u1");

        verify(cacheRepository, never()).save(eq("u1"), any());
    }

    @Test
    void refreshIfPresentRebuildsWhenCacheExists() {
        RedisCart cart = new RedisCart("u1");
        CartValidationContext context = new CartValidationContext(new CartValidationResult(), Map.of(), null);
        CartCalculationResult calculationResult = new CartCalculationResult(Map.of(), Map.of());
        CartSummaryResponse built = new CartSummaryResponse("c1", List.of(), 0, List.of(), null, null);
        when(cacheRepository.existsByUserId("u1")).thenReturn(true);
        when(cartRedisRepository.findByUserId("u1")).thenReturn(Optional.of(cart));
        when(cartValidationService.validateReadOnly(cart)).thenReturn(context);
        when(cartCalculationService.calculate(cart, context.validationResult())).thenReturn(calculationResult);
        when(getCartResponseBuilder.build(cart, context, calculationResult)).thenReturn(built);

        service.refreshCachedCartSummaryResponseIfPresent("u1");

        verify(cacheRepository).save("u1", built);
    }
}
