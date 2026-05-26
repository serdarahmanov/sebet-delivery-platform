package com.sebet.cartservice.cart.controller;

import com.sebet.cartservice.cart.model.CartPromoCodeResponse;
import com.sebet.cartservice.cart.model.StoreBasket;
import com.sebet.cartservice.cart.enums.PromoCodeState;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromoSelectionType;
import com.sebet.cartservice.cart.checkout.service.CheckoutService;
import com.sebet.cartservice.cart.service.CartResponseCacheService;
import com.sebet.cartservice.cart.service.CartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CartControllerPromoEndpointsTest {

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
    void claimEndpointReturnsStoreBasketAndUsesHeaderUserId() throws Exception {
        when(cartService.claimPromoCode(eq("user-123"), eq("store-1"), eq(new com.sebet.cartservice.cart.dto.ClaimPromoCodeRequest("WELCOME10"))))
                .thenReturn(sampleBasket("store-1", "WELCOME10", PromoCodeState.SAVED, false, false));

        mockMvc.perform(post("/api/cart/store-baskets/store-1/promo-codes/claim")
                        .header("X-User-Id", "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"WELCOME10"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value("store-1"))
                .andExpect(jsonPath("$.promoCodes[0].code").value("WELCOME10"))
                .andExpect(jsonPath("$.promoCodes[0].state").value("SAVED"));

        verify(cartService).claimPromoCode(eq("user-123"), eq("store-1"), eq(new com.sebet.cartservice.cart.dto.ClaimPromoCodeRequest("WELCOME10")));
    }

    @Test
    void applyEndpointReturnsStoreBasketAndUsesHeaderUserId() throws Exception {
        when(cartService.applyPromoCodes(eq("user-123"), eq("store-1"), eq(new com.sebet.cartservice.cart.dto.ApplyPromoCodesRequest(
                List.of(
                        new com.sebet.cartservice.cart.dto.ApplyPromoCodeSelectionRequest("WELCOME10", true),
                        new com.sebet.cartservice.cart.dto.ApplyPromoCodeSelectionRequest("EXCLUSIVE100", false)
                )
        )))).thenReturn(sampleBasket("store-1", "WELCOME10", PromoCodeState.SELECTED, true, true));

        mockMvc.perform(post("/api/cart/store-baskets/store-1/promo-codes/apply")
                        .header("X-User-Id", "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "promoCodes":[
                                    {"code":"WELCOME10","selected":true},
                                    {"code":"EXCLUSIVE100","selected":false}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value("store-1"))
                .andExpect(jsonPath("$.promoCodes[0].state").value("SELECTED"))
                .andExpect(jsonPath("$.promoCodes[0].selected").value(true))
                .andExpect(jsonPath("$.promoCodes[0].applied").value(true));
    }

    @Test
    void deleteEndpointReturnsStoreBasketAndUsesHeaderUserId() throws Exception {
        when(cartService.deletePromoCode(eq("user-123"), eq("store-1"), eq("WELCOME10")))
                .thenReturn(sampleBasket("store-1", "SAVE50", PromoCodeState.SAVED, false, false));

        mockMvc.perform(delete("/api/cart/store-baskets/store-1/promo-codes/WELCOME10")
                        .header("X-User-Id", "user-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value("store-1"));

        verify(cartService).deletePromoCode("user-123", "store-1", "WELCOME10");
    }

    private StoreBasket sampleBasket(
            String storeId,
            String code,
            PromoCodeState state,
            boolean selected,
            boolean applied
    ) {
        return new StoreBasket(
                "basket:" + storeId,
                storeId,
                "Store",
                true,
                true,
                null,    // addressId
                null,    // deliveryQuote
                null,    // selectedDeliveryMethodId
                List.of(), // availableDeliveryOptions
                null,    // scheduleType
                null,    // scheduledFor
                null,    // summary
                List.of(
                        new CartPromoCodeResponse(
                                code,
                                "Promo",
                                "promo-1",
                                null,
                                PromoSelectionType.STACKABLE,
                                state,
                                selected,
                                true,
                                applied,
                                BigDecimal.ZERO,
                                null,
                                Instant.parse("2026-06-01T00:00:00Z"),
                                1,
                                0,
                                "ok",
                                List.of()
                        )
                ),
                List.of(), // issues
                List.of(), // storeIssues
                List.of(), // items
                Instant.parse("2026-05-15T14:00:00Z"),
                Instant.parse("2026-05-15T14:00:00Z")
        );
    }
}
