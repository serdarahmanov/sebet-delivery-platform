package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.dto.BatchCartItemRequest;
import com.sebet.cartservice.cart.dto.BatchUpsertCartItemsRequest;
import com.sebet.cartservice.cart.dto.BatchUpsertResponse;
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
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceBatchUpsertItemsTest {

    @Mock
    private CartRedisRepository cartRedisRepository;
    @Mock
    private CartResponseCacheService cartResponseCacheService;
    @Mock
    private CartResponseBuilder cartResponseBuilder;
    @Mock
    private CartLockService cartLockService;
    @Mock
    private CartMetrics cartMetrics;
    @Mock
    private StoreBasketCacheService storeBasketCacheService;
    @InjectMocks
    private CartService cartService;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @BeforeEach
    void stubLock() {
        lenient().doAnswer(inv -> ((Supplier) inv.getArgument(1)).get())
                .when(cartLockService).withLock(anyString(), any(Supplier.class));
    }

    @Test
    void existingItemQuantityIsUpdated() {
        RedisCart cart = new RedisCart("u1");
        cart.findOrCreateBasket("store-1").addItem(
                new RedisCartItem("apple-1", "store-1", BigDecimal.ONE));
        when(cartRedisRepository.findByUserId("u1")).thenReturn(Optional.of(cart));

        cartService.batchUpsertItems("u1", new BatchUpsertCartItemsRequest(List.of(
                new BatchCartItemRequest("store-1", "apple-1", BigDecimal.valueOf(2))
        )));

        ArgumentCaptor<RedisCart> captor = ArgumentCaptor.forClass(RedisCart.class);
        verify(cartRedisRepository).save(eq("u1"), captor.capture());
        RedisCartItem item = captor.getValue().getItems().get(0);
        assertThat(item.getQuantity()).isEqualByComparingTo("2");
    }

    @Test
    void newItemIsAdded() {
        RedisCart cart = new RedisCart("u1");
        cart.findOrCreateBasket("store-1").addItem(
                new RedisCartItem("apple-1", "store-1", BigDecimal.ONE));
        when(cartRedisRepository.findByUserId("u1")).thenReturn(Optional.of(cart));

        cartService.batchUpsertItems("u1", new BatchUpsertCartItemsRequest(List.of(
                new BatchCartItemRequest("store-1", "milk-1", BigDecimal.ONE)
        )));

        ArgumentCaptor<RedisCart> captor = ArgumentCaptor.forClass(RedisCart.class);
        verify(cartRedisRepository).save(eq("u1"), captor.capture());
        assertThat(captor.getValue().getItems())
                .anyMatch(i -> "store-1".equals(i.getStoreId())
                        && "milk-1".equals(i.getProductId())
                        && i.getQuantity().compareTo(BigDecimal.ONE) == 0);
    }

    @Test
    void existingItemsNotIncludedInRequestAreKept() {
        RedisCart cart = new RedisCart("u1");
        cart.findOrCreateBasket("store-1").addItem(
                new RedisCartItem("apple-1", "store-1", BigDecimal.ONE));
        cart.findOrCreateBasket("store-1").addItem(
                new RedisCartItem("bread-1", "store-1", BigDecimal.ONE));
        when(cartRedisRepository.findByUserId("u1")).thenReturn(Optional.of(cart));

        cartService.batchUpsertItems("u1", new BatchUpsertCartItemsRequest(List.of(
                new BatchCartItemRequest("store-1", "apple-1", BigDecimal.valueOf(2))
        )));

        ArgumentCaptor<RedisCart> captor = ArgumentCaptor.forClass(RedisCart.class);
        verify(cartRedisRepository).save(eq("u1"), captor.capture());
        assertThat(captor.getValue().getItems())
                .anyMatch(i -> "bread-1".equals(i.getProductId()) && "store-1".equals(i.getStoreId()));
    }

    @Test
    void itemsAreAppliedByStoreId() {
        RedisCart cart = new RedisCart("u1");
        cart.findOrCreateBasket("store-1").addItem(
                new RedisCartItem("apple-1", "store-1", BigDecimal.ONE));
        cart.findOrCreateBasket("store-2").addItem(
                new RedisCartItem("water-1", "store-2", BigDecimal.ONE));
        when(cartRedisRepository.findByUserId("u1")).thenReturn(Optional.of(cart));

        cartService.batchUpsertItems("u1", new BatchUpsertCartItemsRequest(List.of(
                new BatchCartItemRequest("store-1", "apple-1", BigDecimal.valueOf(3)),
                new BatchCartItemRequest("store-2", "water-1", BigDecimal.valueOf(4))
        )));

        ArgumentCaptor<RedisCart> captor = ArgumentCaptor.forClass(RedisCart.class);
        verify(cartRedisRepository).save(eq("u1"), captor.capture());
        RedisCart saved = captor.getValue();
        assertThat(saved.getItems()).anyMatch(i -> "store-1".equals(i.getStoreId())
                && "apple-1".equals(i.getProductId())
                && i.getQuantity().compareTo(BigDecimal.valueOf(3)) == 0);
        assertThat(saved.getItems()).anyMatch(i -> "store-2".equals(i.getStoreId())
                && "water-1".equals(i.getProductId())
                && i.getQuantity().compareTo(BigDecimal.valueOf(4)) == 0);
    }

    @Test
    void createsNewCartWhenUserHasNoCart() {
        when(cartRedisRepository.findByUserId("u1")).thenReturn(Optional.empty());

        BatchUpsertResponse result = cartService.batchUpsertItems("u1", new BatchUpsertCartItemsRequest(List.of(
                new BatchCartItemRequest("store-1", "apple-1", BigDecimal.ONE)
        )));

        // First save: new empty cart; second save: cart with the item
        verify(cartRedisRepository, times(2)).save(eq("u1"), any(RedisCart.class));
        assertThat(result.basketIds()).hasSize(1);
        assertThat(result.basketIds().get(0)).contains("store-1");
    }
}
