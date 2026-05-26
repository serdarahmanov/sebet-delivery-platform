package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.dto.ApplyPromoCodeRequest;
import com.sebet.cartservice.cart.dto.Cart;
import com.sebet.cartservice.cart.enums.IssueScope;
import com.sebet.cartservice.cart.enums.IssueSeverity;
import com.sebet.cartservice.cart.enums.PromoCodeState;
import com.sebet.cartservice.cart.metrics.CartMetrics;
import com.sebet.cartservice.cart.model.CartPromoCodeResponse;
import com.sebet.cartservice.cart.model.StoreBasket;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.issue.PromoCodeIssueCode;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.issue.PromoIssue;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromoSelectionType;
import com.sebet.cartservice.cart.repository.CartRedisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceApplyPromoCodeTest {

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
    void validSecondPromoIsClaimedAndStored() {
        RedisCart cart = new RedisCart("u1");
        cart.upsertPromoCode("store-1", "OLD10", PromoCodeState.SELECTED);
        when(cartRedisRepository.findByUserId("u1")).thenReturn(Optional.of(cart));

        Cart applicablePartial = partialForStoreWithPromo("store-1", "NEW20", true);
        when(cartResponseBuilder.build(any(RedisCart.class), eq(Set.of("store-1")))).thenReturn(applicablePartial);
        when(cartResponseBuilder.build(any(RedisCart.class))).thenReturn(applicablePartial);

        cartService.applyPromoCode("u1", "store-1", new ApplyPromoCodeRequest("NEW20"));

        ArgumentCaptor<RedisCart> cartCaptor = ArgumentCaptor.forClass(RedisCart.class);
        verify(cartRedisRepository).save(eq("u1"), cartCaptor.capture());
        RedisCart saved = cartCaptor.getValue();

        assertThat(saved.getPromoCodes()).hasSize(2);
        assertThat(saved.getPromoCodes()).anyMatch(promo -> "OLD10".equals(promo.getCode()));
        assertThat(saved.getPromoCodes()).anyMatch(promo -> "NEW20".equals(promo.getCode()));
    }

    @Test
    void invalidSecondPromoIsNotStored() {
        RedisCart cart = new RedisCart("u1");
        cart.upsertPromoCode("store-1", "OLD10", PromoCodeState.SELECTED);
        when(cartRedisRepository.findByUserId("u1")).thenReturn(Optional.of(cart));

        Cart invalidPartial = partialForStoreWithPromo("store-1", "NEW20", false, true);
        Cart oldStatePartial = partialForStoreWithPromo("store-1", "OLD10", true);
        when(cartResponseBuilder.build(any(RedisCart.class), eq(Set.of("store-1"))))
                .thenReturn(invalidPartial)
                .thenReturn(oldStatePartial);
        when(cartResponseBuilder.build(any(RedisCart.class))).thenReturn(oldStatePartial);

        cartService.applyPromoCode("u1", "store-1", new ApplyPromoCodeRequest("NEW20"));

        assertThat(cart.getPromoCodes()).hasSize(1);
        assertThat(cart.getPromoCodes().get(0).getCode()).isEqualTo("OLD10");
    }

    private Cart partialForStoreWithPromo(String storeId, String code, boolean applied) {
        return partialForStoreWithPromo(storeId, code, applied, false);
    }

    private Cart partialForStoreWithPromo(String storeId, String code, boolean applied, boolean invalid) {
        StoreBasket basket = new StoreBasket(
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
                List.of(new CartPromoCodeResponse(
                        code,
                        "Promo",
                        "promo-id",
                        null,
                        PromoSelectionType.STACKABLE,
                        applied ? PromoCodeState.SELECTED : PromoCodeState.SAVED,
                        applied,
                        applied,
                        applied,
                        BigDecimal.ZERO,
                        null,
                        null,
                        null,
                        null,
                        null,
                        invalid
                                ? List.of(new PromoIssue(
                                PromoCodeIssueCode.PROMO_CODE_INVALID,
                                IssueSeverity.WARNING,
                                IssueScope.PROMO_CODE,
                                "invalid",
                                code,
                                java.util.Map.of()
                        ))
                                : List.of()
                )),
                List.of(), // issues
                List.of(), // storeIssues
                List.of(), // items
                Instant.now(),
                Instant.now()
        );
        return new Cart("cart-1", List.of(basket), BigDecimal.ONE, List.of(), Instant.now(), Instant.now());
    }
}
