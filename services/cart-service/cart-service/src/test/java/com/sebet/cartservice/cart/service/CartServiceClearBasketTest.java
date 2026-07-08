package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.enums.PromoCodeState;
import com.sebet.cartservice.cart.metrics.CartMetrics;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import com.sebet.cartservice.cart.repository.CartRedisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceClearBasketTest {

    @Mock
    private CartRedisRepository cartRedisRepository;
    @Mock
    private CartResponseCacheService cartResponseCacheService;
    @Mock
    private CartResponseBuilder cartResponseBuilder;
    @Mock
    private CartMetrics cartMetrics;
    @Mock
    private StoreBasketCacheService storeBasketCacheService;
    @InjectMocks
    private CartService cartService;

    @BeforeEach
    void stubVersionedSave() {
        lenient().when(cartRedisRepository.saveIfVersionMatches(anyString(), any(RedisCart.class), anyLong()))
                .thenReturn(true);
    }

    @Test
    void clearBasketRemovesEntireBasketForStore() {
        RedisCart cart = new RedisCart("u1");
        // basket for store-1 with item and promo
        cart.findOrCreateBasket("store-1").addItem(
                new RedisCartItem("apple-1", "store-1", BigDecimal.ONE));
        cart.upsertPromoCode("store-1", "WELCOME10", PromoCodeState.SAVED);
        // basket for store-2 stays intact
        cart.findOrCreateBasket("store-2").addItem(
                new RedisCartItem("bread-1", "store-2", BigDecimal.ONE));
        cart.upsertPromoCode("store-2", "FREESHIP", PromoCodeState.SAVED);

        String basketId = cart.getCartId() + ":store-1";
        when(cartRedisRepository.findByUserId("u1")).thenReturn(Optional.of(cart));

        cartService.clearBasket("u1", basketId);

        ArgumentCaptor<RedisCart> captor = ArgumentCaptor.forClass(RedisCart.class);
        verify(cartRedisRepository).saveIfVersionMatches(eq("u1"), captor.capture(), anyLong());
        RedisCart saved = captor.getValue();

        assertThat(saved.getItems()).noneMatch(item -> "store-1".equals(item.getStoreId()));
        assertThat(saved.getItems()).anyMatch(item -> "store-2".equals(item.getStoreId()));
        assertThat(saved.getPromoCodes()).noneMatch(p -> "WELCOME10".equals(p.getCode()));
        assertThat(saved.getPromoCodes()).anyMatch(p -> "FREESHIP".equals(p.getCode()));
        verify(cartResponseCacheService).evict("u1");
    }

    @Test
    void clearBasketDoesNotSaveWhenStoreNotFound() {
        RedisCart cart = new RedisCart("u1");
        cart.findOrCreateBasket("store-2").addItem(
                new RedisCartItem("bread-1", "store-2", BigDecimal.ONE));
        when(cartRedisRepository.findByUserId("u1")).thenReturn(Optional.of(cart));

        // BasketId doesn't match any basket in this cart
        String wrongBasketId = cart.getCartId() + ":store-999";
        cartService.clearBasket("u1", wrongBasketId);

        verify(cartRedisRepository, never()).saveIfVersionMatches(eq("u1"), any(RedisCart.class), anyLong());
    }

    @Test
    void clearBasketDoesNotSaveWhenBasketIdFormatIsInvalid() {
        RedisCart cart = new RedisCart("u1");
        cart.findOrCreateBasket("store-1").addItem(
                new RedisCartItem("apple-1", "store-1", BigDecimal.ONE));
        when(cartRedisRepository.findByUserId("u1")).thenReturn(Optional.of(cart));

        // Raw storeId without cartId prefix — resolveStoreIdFromBasketId returns null
        cartService.clearBasket("u1", "store-1");

        verify(cartRedisRepository, never()).saveIfVersionMatches(eq("u1"), any(RedisCart.class), anyLong());
    }
}
