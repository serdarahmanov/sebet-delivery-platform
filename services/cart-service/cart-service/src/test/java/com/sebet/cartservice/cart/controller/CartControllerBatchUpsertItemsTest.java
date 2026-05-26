package com.sebet.cartservice.cart.controller;

import com.sebet.cartservice.cart.checkout.service.CheckoutService;
import com.sebet.cartservice.cart.dto.BatchUpsertCartItemsRequest;
import com.sebet.cartservice.cart.dto.BatchUpsertResponse;
import com.sebet.cartservice.cart.service.CartResponseCacheService;
import com.sebet.cartservice.cart.service.CartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CartControllerBatchUpsertItemsTest {

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
    void usesXUserIdHeaderAsUserIdSource() throws Exception {
        when(cartService.batchUpsertItems(eq("user-123"), any(BatchUpsertCartItemsRequest.class)))
                .thenReturn(new BatchUpsertResponse(List.of("some-cart-id:store-1")));

        String body = """
                {
                  "items": [
                    {
                      "storeId": "store-1",
                      "productId": "apple-1",
                      "quantity": 2
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/cart/items/batch")
                        .header("X-User-Id", "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<BatchUpsertCartItemsRequest> requestCaptor =
                ArgumentCaptor.forClass(BatchUpsertCartItemsRequest.class);
        verify(cartService).batchUpsertItems(eq("user-123"), requestCaptor.capture());

        assertThat(requestCaptor.getValue().items()).hasSize(1);
        assertThat(requestCaptor.getValue().items().get(0).storeId()).isEqualTo("store-1");
    }
}
