package com.sebet.cartservice.cart.controller;

import com.sebet.cartservice.cart.checkout.service.CheckoutService;
import com.sebet.cartservice.cart.model.StoreBasket;
import com.sebet.cartservice.cart.service.CartResponseCacheService;
import com.sebet.cartservice.cart.service.CartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CartControllerValidateStoreBasketTest {

    @Mock
    private CartService cartService;
    @Mock
    private CartResponseCacheService cartResponseCacheService;
    @Mock
    private CheckoutService checkoutService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CartController controller = new CartController(cartService, cartResponseCacheService, checkoutService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getStoreBasketUsesXUserIdHeaderAsUserIdSource() throws Exception {
        StoreBasket stub = new StoreBasket(
                "cart-id:store-1", "store-1", "Store 1", true, true,
                null, null, null, List.of(), null, null, null,
                List.of(), List.of(), List.of(), List.of(),
                Instant.now(), Instant.now()
        );
        when(cartService.getStoreBasket(eq("user-123"), eq("cart-id:store-1"))).thenReturn(stub);

        mockMvc.perform(get("/api/cart/store-baskets/cart-id:store-1")
                        .header("X-User-Id", "user-123"))
                .andExpect(status().isOk());

        verify(cartService).getStoreBasket("user-123", "cart-id:store-1");
    }
}
