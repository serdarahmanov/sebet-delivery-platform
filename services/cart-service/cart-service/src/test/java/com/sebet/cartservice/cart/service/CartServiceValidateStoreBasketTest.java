package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.dto.Cart;
import com.sebet.cartservice.cart.model.StoreBasket;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import com.sebet.cartservice.cart.metrics.CartMetrics;
import com.sebet.cartservice.cart.repository.CartRedisRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceValidateStoreBasketTest {
    @Mock
    private CartRedisRepository cartRedisRepository;
    @Mock
    private CartResponseCacheService cartResponseCacheService;
    @Mock
    private CartResponseBuilder cartResponseBuilder;
    @Mock
    private StoreBasketCacheService storeBasketCacheService;
    @Mock
    private CartMetrics cartMetrics;
    @InjectMocks
    private CartService cartService;

    @Test
    void getStoreBasketCallsBuildReadOnlyWithCorrectStoreScope() {
        RedisCart cart = new RedisCart("u1");
        cart.findOrCreateBasket("store-1").addItem(
                new RedisCartItem("p1", "store-1", BigDecimal.ONE)
        );
        String basketId = cart.getCartId() + ":store-1";

        when(cartRedisRepository.findByUserId("u1")).thenReturn(Optional.of(cart));
        when(storeBasketCacheService.getIfPresent("u1", "store-1")).thenReturn(Optional.empty());

        StoreBasket stub = new StoreBasket(
                basketId, "store-1", "Store 1", true, true,
                null, null, null, List.of(), null, null, null,
                List.of(), List.of(), List.of(), List.of(),
                Instant.now(), Instant.now()
        );
        Cart partial = new Cart(cart.getCartId(), List.of(stub), BigDecimal.ONE, List.of(), Instant.now(), Instant.now());
        when(cartResponseBuilder.buildReadOnly(cart, Set.of("store-1"))).thenReturn(partial);

        StoreBasket result = cartService.getStoreBasket("u1", basketId);

        verify(cartResponseBuilder).buildReadOnly(cart, Set.of("store-1"));
        verify(cartResponseBuilder, never()).build(any(RedisCart.class), any());
        assertThat(result.storeId()).isEqualTo("store-1");
    }

    @Test
    void getStoreBasketThrowsNotFoundWhenBasketDoesNotExist() {
        RedisCart cart = new RedisCart("u1");
        String basketId = cart.getCartId() + ":store-404";
        when(cartRedisRepository.findByUserId("u1")).thenReturn(Optional.of(cart));

        assertThatThrownBy(() -> cartService.getStoreBasket("u1", basketId))
                .isInstanceOf(ResponseStatusException.class);
    }
}
