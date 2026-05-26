package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.dto.ApplyPromoCodeSelectionRequest;
import com.sebet.cartservice.cart.dto.ApplyPromoCodesRequest;
import com.sebet.cartservice.cart.dto.Cart;
import com.sebet.cartservice.cart.dto.ClaimPromoCodeRequest;
import com.sebet.cartservice.cart.enums.IssueScope;
import com.sebet.cartservice.cart.enums.IssueSeverity;
import com.sebet.cartservice.cart.enums.PromoCodeState;
import com.sebet.cartservice.cart.metrics.CartMetrics;
import com.sebet.cartservice.cart.model.CartPromoCodeResponse;
import com.sebet.cartservice.cart.model.StoreBasket;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.issue.PromoCodeIssueCode;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.issue.PromoIssue;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromoSelectionType;
import com.sebet.cartservice.cart.repository.CartRedisRepository;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServicePromoFlowTest {

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
        doAnswer(inv -> ((Supplier) inv.getArgument(1)).get())
                .when(cartLockService).withLock(anyString(), any(Supplier.class));
    }

    @Test
    void claimValidPromoStoresAsSaved() {
        RedisCart cart = new RedisCart("u1");
        when(cartRedisRepository.findByUserId("u1")).thenReturn(Optional.of(cart));

        Cart candidate = cartResponseFor("store-1", List.of(
                promo("WELCOME10", PromoCodeState.SAVED, false, true, false, PromoSelectionType.STACKABLE, List.of())
        ));
        when(cartResponseBuilder.build(any(RedisCart.class), anySet())).thenReturn(candidate);

        cartService.claimPromoCode("u1", "store-1", new ClaimPromoCodeRequest("WELCOME10"));

        ArgumentCaptor<RedisCart> captor = ArgumentCaptor.forClass(RedisCart.class);
        verify(cartRedisRepository).save(eq("u1"), captor.capture());
        RedisCart saved = captor.getValue();
        assertThat(saved.getPromoCodes()).hasSize(1);
        assertThat(saved.getPromoCodes().get(0).getCode()).isEqualTo("WELCOME10");
        assertThat(saved.getPromoCodes().get(0).getState()).isEqualTo(PromoCodeState.SAVED);
        assertThat(saved.getPromoCodes().get(0).getSelectedAt()).isNull();
    }

    @Test
    void claimInvalidPromoDoesNotPersist() {
        RedisCart cart = new RedisCart("u1");
        when(cartRedisRepository.findByUserId("u1")).thenReturn(Optional.of(cart));

        PromoIssue invalidIssue = new PromoIssue(
                PromoCodeIssueCode.PROMO_CODE_INVALID,
                IssueSeverity.WARNING,
                IssueScope.PROMO_CODE,
                "Invalid",
                "BAD",
                java.util.Map.of()
        );
        Cart invalid = cartResponseFor("store-1", List.of(
                promo("BAD", PromoCodeState.SAVED, false, false, false, PromoSelectionType.STACKABLE, List.of(invalidIssue))
        ));
        when(cartResponseBuilder.build(any(RedisCart.class), anySet())).thenReturn(invalid);

        cartService.claimPromoCode("u1", "store-1", new ClaimPromoCodeRequest("BAD"));

        verify(cartRedisRepository, times(0)).save(eq("u1"), any(RedisCart.class));
        assertThat(cart.getPromoCodes()).isEmpty();
    }

    @Test
    void applyRejectsUnclaimedCodes() {
        RedisCart cart = new RedisCart("u1");
        cart.upsertPromoCode("store-1", "WELCOME10", PromoCodeState.SAVED);
        when(cartRedisRepository.findByUserId("u1")).thenReturn(Optional.of(cart));

        ApplyPromoCodesRequest request = new ApplyPromoCodesRequest(List.of(
                new ApplyPromoCodeSelectionRequest("UNKNOWN", true)
        ));

        assertThatThrownBy(() -> cartService.applyPromoCodes("u1", "store-1", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Promo code was not claimed");
    }

    @Test
    void applyNormalizesExclusiveSelection() {
        RedisCart cart = new RedisCart("u1");
        cart.upsertPromoCode("store-1", "WELCOME10", PromoCodeState.SELECTED);
        cart.upsertPromoCode("store-1", "EXCLUSIVE100", PromoCodeState.SELECTED);
        when(cartRedisRepository.findByUserId("u1")).thenReturn(Optional.of(cart));

        Cart normalized = cartResponseFor("store-1", List.of(
                promo("WELCOME10", PromoCodeState.SAVED, false, true, false, PromoSelectionType.STACKABLE, List.of()),
                promo("EXCLUSIVE100", PromoCodeState.SELECTED, true, true, true, PromoSelectionType.EXCLUSIVE, List.of())
        ));
        when(cartResponseBuilder.build(any(RedisCart.class), anySet())).thenReturn(normalized);

        ApplyPromoCodesRequest request = new ApplyPromoCodesRequest(List.of(
                new ApplyPromoCodeSelectionRequest("WELCOME10", true),
                new ApplyPromoCodeSelectionRequest("EXCLUSIVE100", true)
        ));

        cartService.applyPromoCodes("u1", "store-1", request);

        ArgumentCaptor<RedisCart> captor = ArgumentCaptor.forClass(RedisCart.class);
        verify(cartRedisRepository).save(eq("u1"), captor.capture());
        RedisCart saved = captor.getValue();
        var savedW = saved.getPromoCodes().stream()
                .filter(p -> "WELCOME10".equals(p.getCode())).findFirst().orElseThrow();
        var savedEx = saved.getPromoCodes().stream()
                .filter(p -> "EXCLUSIVE100".equals(p.getCode())).findFirst().orElseThrow();

        assertThat(savedW.getState()).isEqualTo(PromoCodeState.SAVED);
        assertThat(savedEx.getState()).isEqualTo(PromoCodeState.SELECTED);
    }

    @Test
    void deleteRemovesPromoCompletelyFromRedis() {
        RedisCart cart = new RedisCart("u1");
        cart.upsertPromoCode("store-1", "WELCOME10", PromoCodeState.SAVED);
        cart.upsertPromoCode("store-1", "SAVE50", PromoCodeState.SAVED);
        when(cartRedisRepository.findByUserId("u1")).thenReturn(Optional.of(cart));

        Cart response = cartResponseFor("store-1", List.of(
                promo("SAVE50", PromoCodeState.SAVED, false, true, false, PromoSelectionType.STACKABLE, List.of())
        ));
        when(cartResponseBuilder.build(any(RedisCart.class), anySet())).thenReturn(response);

        cartService.deletePromoCode("u1", "store-1", "WELCOME10");

        ArgumentCaptor<RedisCart> captor = ArgumentCaptor.forClass(RedisCart.class);
        verify(cartRedisRepository).save(eq("u1"), captor.capture());
        assertThat(captor.getValue().getPromoCodes())
                .allMatch(p -> !"WELCOME10".equals(p.getCode()));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private CartPromoCodeResponse promo(
            String code,
            PromoCodeState state,
            boolean selected,
            boolean canBeSelected,
            boolean applied,
            PromoSelectionType selectionType,
            List<PromoIssue> issues
    ) {
        return new CartPromoCodeResponse(
                code,
                "Promo",
                "promo-" + code,
                null,
                selectionType,
                state,
                selected,
                canBeSelected,
                applied,
                BigDecimal.ZERO,
                null,
                null,
                null,
                null,
                "msg",
                issues
        );
    }

    private Cart cartResponseFor(String storeId, List<CartPromoCodeResponse> promoCodes) {
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
                promoCodes,
                List.of(), // issues
                List.of(), // storeIssues
                List.of(), // items
                Instant.now(),
                Instant.now()
        );
        return new Cart("cart-1", List.of(basket), BigDecimal.ONE, List.of(), Instant.now(), Instant.now());
    }
}
