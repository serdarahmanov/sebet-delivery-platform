package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.dto.AddCartItemRequest;
import com.sebet.cartservice.cart.dto.ApplyPromoCodeRequest;
import com.sebet.cartservice.cart.dto.CartResponse;
import com.sebet.cartservice.cart.dto.UpdateCartItemRequest;
import com.sebet.cartservice.cart.mapper.RedisCartMapper;
import com.sebet.cartservice.cart.model.cart_calculation.CartCalculationResult;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import com.sebet.cartservice.cart.model.redis.RedisCartPromoCode;
import com.sebet.cartservice.cart.repository.CartRedisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    private static final String USER_ID = "user-1";

    @Mock
    private CartRedisRepository cartRedisRepository;

    @Mock
    private CartValidationService cartValidationService;

    @Mock
    private CartCalculationService cartCalculationService;

    @Mock
    private RedisCartMapper redisCartMapper;

    @InjectMocks
    private CartService cartService;

    @Captor
    private ArgumentCaptor<RedisCart> cartCaptor;

    private CartResponse response;
    private CartValidationResult validationResult;
    private CartCalculationResult calculationResult;

    @BeforeEach
    void setUp() {
        response = new CartResponse("cart-1", List.of(), BigDecimal.ZERO, List.of(), Instant.now(), Instant.now());
        validationResult = org.mockito.Mockito.mock(CartValidationResult.class);
        calculationResult = org.mockito.Mockito.mock(CartCalculationResult.class);

        when(cartValidationService.validate(any(RedisCart.class))).thenReturn(validationResult);
        when(cartCalculationService.calculate(any(RedisCart.class), eq(validationResult))).thenReturn(calculationResult);
        when(redisCartMapper.toCartResponse(any(RedisCart.class), eq(validationResult), eq(calculationResult)))
                .thenReturn(response);
    }

    @Test
    void addItemToCart_addsNewItemWhenNoExistingMatch() {
        RedisCart cart = baseCart();
        cart.setItems(new ArrayList<>());
        when(cartRedisRepository.findByUserId(USER_ID)).thenReturn(Optional.of(cart));

        AddCartItemRequest request = new AddCartItemRequest("p1", "s1", new BigDecimal("2.500"));

        CartResponse actual = cartService.addItemToCart(USER_ID, request);

        verify(cartRedisRepository).save(eq(USER_ID), cartCaptor.capture());
        RedisCart saved = cartCaptor.getValue();

        assertEquals(1, saved.getItems().size());
        assertEquals("p1", saved.getItems().get(0).getProductId());
        assertEquals("s1", saved.getItems().get(0).getStoreId());
        assertEquals(new BigDecimal("2.500"), saved.getItems().get(0).getQuantity());
        assertNotNull(saved.getUpdatedAt());
        assertEquals(response, actual);
    }

    @Test
    void addItemToCart_increasesQuantityForExistingProductAndStore() {
        RedisCartItem existing = new RedisCartItem("id-1", "p1", "s1", new BigDecimal("1.000"), Instant.now(), null, Instant.now());
        RedisCart cart = baseCart();
        cart.setItems(new ArrayList<>(List.of(existing)));
        when(cartRedisRepository.findByUserId(USER_ID)).thenReturn(Optional.of(cart));

        AddCartItemRequest request = new AddCartItemRequest("p1", "s1", new BigDecimal("0.500"));

        cartService.addItemToCart(USER_ID, request);

        verify(cartRedisRepository).save(eq(USER_ID), cartCaptor.capture());
        RedisCart saved = cartCaptor.getValue();

        assertEquals(1, saved.getItems().size());
        assertEquals(new BigDecimal("1.500"), saved.getItems().get(0).getQuantity());
    }

    @Test
    void updateCartItemQuantity_updatesQuantityForExistingItem() {
        RedisCartItem item = new RedisCartItem("item-1", "p1", "s1", new BigDecimal("1.000"), Instant.now(), null, Instant.now());
        RedisCart cart = baseCart();
        cart.setItems(new ArrayList<>(List.of(item)));
        when(cartRedisRepository.findByUserId(USER_ID)).thenReturn(Optional.of(cart));

        cartService.updateCartItemQuantity(USER_ID, "item-1", new UpdateCartItemRequest(new BigDecimal("3.000")));

        verify(cartRedisRepository).save(eq(USER_ID), cartCaptor.capture());
        RedisCart saved = cartCaptor.getValue();
        assertEquals(new BigDecimal("3.000"), saved.getItems().get(0).getQuantity());
    }

    @Test
    void removeCartItem_removesAndSavesWhenItemExists() {
        RedisCartItem item = new RedisCartItem("item-1", "p1", "s1", new BigDecimal("1.000"), Instant.now(), null, Instant.now());
        RedisCart cart = baseCart();
        cart.setItems(new ArrayList<>(List.of(item)));
        when(cartRedisRepository.findByUserId(USER_ID)).thenReturn(Optional.of(cart));

        cartService.removeCartItem(USER_ID, "item-1");

        verify(cartRedisRepository).save(eq(USER_ID), cartCaptor.capture());
        assertEquals(0, cartCaptor.getValue().getItems().size());
    }

    @Test
    void applyPromoCode_replacesExistingPromoForSameStore() {
        RedisCart cart = baseCart();
        cart.setPromoCodes(new ArrayList<>(List.of(
                new RedisCartPromoCode("store-1", "OLD", Instant.now()),
                new RedisCartPromoCode("store-2", "KEEP", Instant.now())
        )));
        when(cartRedisRepository.findByUserId(USER_ID)).thenReturn(Optional.of(cart));

        cartService.applyPromoCode(USER_ID, "store-1", new ApplyPromoCodeRequest("NEW"));

        verify(cartRedisRepository).save(eq(USER_ID), cartCaptor.capture());
        RedisCart saved = cartCaptor.getValue();

        assertEquals(2, saved.getPromoCodes().size());
        assertEquals(1, saved.getPromoCodes().stream().filter(p -> "store-1".equals(p.getStoreId())).count());
        assertEquals("NEW", saved.getPromoCodes().stream()
                .filter(p -> "store-1".equals(p.getStoreId()))
                .findFirst()
                .orElseThrow()
                .getCode());
    }

    @Test
    void removePromoCode_removesCaseInsensitivelyAndSaves() {
        RedisCart cart = baseCart();
        cart.setPromoCodes(new ArrayList<>(List.of(new RedisCartPromoCode("store-1", "SaVe10", Instant.now()))));
        when(cartRedisRepository.findByUserId(USER_ID)).thenReturn(Optional.of(cart));

        cartService.removePromoCode(USER_ID, "store-1", "save10");

        verify(cartRedisRepository).save(eq(USER_ID), cartCaptor.capture());
        assertEquals(0, cartCaptor.getValue().getPromoCodes().size());
    }

    @Test
    void clearBasket_removesItemsAndStorePromoByStoreId() {
        RedisCart cart = baseCart();
        cart.setItems(new ArrayList<>(List.of(
                new RedisCartItem("i1", "p1", "store-1", BigDecimal.ONE, Instant.now(), null, Instant.now()),
                new RedisCartItem("i2", "p2", "store-2", BigDecimal.ONE, Instant.now(), null, Instant.now())
        )));
        cart.setPromoCodes(new ArrayList<>(List.of(
                new RedisCartPromoCode("store-1", "A", Instant.now()),
                new RedisCartPromoCode("store-2", "B", Instant.now())
        )));
        when(cartRedisRepository.findByUserId(USER_ID)).thenReturn(Optional.of(cart));

        cartService.clearBasket(USER_ID, "store-1");

        verify(cartRedisRepository).save(eq(USER_ID), cartCaptor.capture());
        RedisCart saved = cartCaptor.getValue();

        assertEquals(1, saved.getItems().size());
        assertEquals("store-2", saved.getItems().get(0).getStoreId());
        assertEquals(1, saved.getPromoCodes().size());
        assertEquals("store-2", saved.getPromoCodes().get(0).getStoreId());
    }

    @Test
    void clearBasket_removesItemsAndStorePromoByBasketIdPattern() {
        RedisCart cart = baseCart();
        cart.setCartId("cart-xyz");
        cart.setItems(new ArrayList<>(List.of(
                new RedisCartItem("i1", "p1", "store-1", BigDecimal.ONE, Instant.now(), null, Instant.now()),
                new RedisCartItem("i2", "p2", "store-2", BigDecimal.ONE, Instant.now(), null, Instant.now())
        )));
        cart.setPromoCodes(new ArrayList<>(List.of(
                new RedisCartPromoCode("store-1", "A", Instant.now()),
                new RedisCartPromoCode("store-2", "B", Instant.now())
        )));
        when(cartRedisRepository.findByUserId(USER_ID)).thenReturn(Optional.of(cart));

        cartService.clearBasket(USER_ID, "cart-xyz:store-1");

        verify(cartRedisRepository).save(eq(USER_ID), cartCaptor.capture());
        RedisCart saved = cartCaptor.getValue();

        assertEquals(1, saved.getItems().size());
        assertEquals("store-2", saved.getItems().get(0).getStoreId());
        assertEquals(1, saved.getPromoCodes().size());
        assertEquals("store-2", saved.getPromoCodes().get(0).getStoreId());
    }

    @Test
    void removePromoCode_doesNotSaveWhenNothingRemoved() {
        RedisCart cart = baseCart();
        cart.setPromoCodes(new ArrayList<>(List.of(new RedisCartPromoCode("store-1", "SAVE10", Instant.now()))));
        when(cartRedisRepository.findByUserId(USER_ID)).thenReturn(Optional.of(cart));

        cartService.removePromoCode(USER_ID, "store-2", "SAVE10");

        verify(cartRedisRepository, never()).save(eq(USER_ID), any(RedisCart.class));
    }

    @Test
    void clearBasket_doesNotSaveWhenNoMatchingItems() {
        RedisCart cart = baseCart();
        cart.setItems(new ArrayList<>(List.of(
                new RedisCartItem("i1", "p1", "store-1", BigDecimal.ONE, Instant.now(), null, Instant.now())
        )));
        when(cartRedisRepository.findByUserId(USER_ID)).thenReturn(Optional.of(cart));

        cartService.clearBasket(USER_ID, "store-2");

        verify(cartRedisRepository, never()).save(eq(USER_ID), any(RedisCart.class));
    }

    @Test
    void validateCart_alwaysSavesAndReturnsResponse() {
        RedisCart cart = baseCart();
        when(cartRedisRepository.findByUserId(USER_ID)).thenReturn(Optional.of(cart));

        CartResponse actual = cartService.validateCart(USER_ID);

        verify(cartRedisRepository, times(1)).save(eq(USER_ID), any(RedisCart.class));
        assertEquals(response, actual);
    }

    private RedisCart baseCart() {
        RedisCart cart = new RedisCart(USER_ID);
        cart.setCartId("cart-1");
        cart.setItems(new ArrayList<>());
        cart.setPromoCodes(new ArrayList<>());
        return cart;
    }
}