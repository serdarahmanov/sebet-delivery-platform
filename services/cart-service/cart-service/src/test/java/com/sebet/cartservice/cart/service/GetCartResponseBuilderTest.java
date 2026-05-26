package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.enums.ItemIssuesCode;
import com.sebet.cartservice.cart.enums.IssueScope;
import com.sebet.cartservice.cart.enums.IssueSeverity;
import com.sebet.cartservice.cart.enums.ProductUnit;
import com.sebet.cartservice.cart.model.cart_calculation.CartCalculationResult;
import com.sebet.cartservice.cart.model.cart_calculation.ItemCalculation;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationContext;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.ProductSnapshot;
import com.sebet.cartservice.cart.model.cart_validation.PromoCodeValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.StoreSnapshot;
import com.sebet.cartservice.cart.model.item.ItemIssue;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import com.sebet.cartservice.cart.model.redis.RedisDeliveryQuote;
import com.sebet.cartservice.cart.model.redis.RedisStoreBasket;
import com.sebet.cartservice.cart.model.store.StoreIssue;
import com.sebet.cartservice.cart.model.store.StoreIssueCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GetCartResponseBuilderTest {

    private final GetCartResponseBuilder builder = new GetCartResponseBuilder();

    // Helper: basket with items and a real delivery quote carrying ETA data
    private RedisStoreBasket basketWithQuote(String storeId, int etaMin, int etaMax,
                                             RedisCartItem... items) {
        RedisStoreBasket basket = new RedisStoreBasket(storeId);
        basket.setItems(new ArrayList<>(List.of(items)));
        basket.setDeliveryQuote(new RedisDeliveryQuote(
                "q1", BigDecimal.valueOf(2), "TMT", "2 TMT",
                etaMin, etaMax, null,
                Instant.now().plusSeconds(300), Instant.now(), null
        ));
        return basket;
    }

    // Helper: basket with items but no delivery quote
    private RedisStoreBasket basketWithoutQuote(String storeId, RedisCartItem... items) {
        RedisStoreBasket basket = new RedisStoreBasket(storeId);
        basket.setItems(new ArrayList<>(List.of(items)));
        return basket;
    }

    @Test
    void filtersHardInvalidItemsAndCalculatesSummaryFromRemainingItems() {
        RedisCartItem valid   = new RedisCartItem("i1", "p1", "s1", BigDecimal.valueOf(2), null, null);
        RedisCartItem blocked = new RedisCartItem("i2", "p2", "s1", BigDecimal.ONE, null, null);

        RedisCart cart = new RedisCart("u1");
        cart.getStoreBaskets().add(basketWithQuote("s1", 25, 35, valid, blocked));

        CartValidationResult validation = new CartValidationResult();
        validation.addStoreSnapshot(new StoreSnapshot("s1", "Store 1", null, true, true, null, null, null));
        validation.addProductSnapshot(new ProductSnapshot("p1", null, "s1", "Apple", "Brand", "Fruit", "img", ProductUnit.PCS,
                null, null, BigDecimal.ONE, BigDecimal.TEN, null, null, null, true, true));
        validation.addProductSnapshot(new ProductSnapshot("p2", null, "s1", "Milk", "Brand", "Dairy", "img", ProductUnit.PCS,
                null, null, BigDecimal.ONE, BigDecimal.valueOf(5), null, null, null, true, true));
        validation.addItemIssue("i2", new ItemIssue(ItemIssuesCode.OUT_OF_STOCK, IssueSeverity.BLOCKING, IssueScope.ITEM, "out", Map.of()));
        validation.putPromoResult(new PromoCodeValidationResult("s1", "WELCOME10", null, null, null, false, false, true,
                BigDecimal.valueOf(3), null, null, null, null, "Promo", List.of()));

        CartCalculationResult calculations = new CartCalculationResult(
                Map.of("i1", new ItemCalculation(
                        "i1", "p1", "s1", BigDecimal.TEN, BigDecimal.valueOf(2),
                        BigDecimal.valueOf(20), BigDecimal.valueOf(2), BigDecimal.valueOf(18)
                )),
                Map.of()
        );

        var response = builder.build(cart, new CartValidationContext(validation, Map.of(), null), calculations);

        assertThat(response.storeBaskets()).hasSize(1);
        var basket = response.storeBaskets().get(0);
        assertThat(basket.items()).hasSize(1);
        assertThat(basket.items().get(0).productId()).isEqualTo("p1");
        assertThat(basket.summary().itemsCount()).isEqualByComparingTo("2");
        assertThat(basket.summary().itemsSubtotalBeforeDiscount()).isEqualByComparingTo("20");
        assertThat(basket.summary().itemsSubtotalAfterDiscount()).isEqualByComparingTo("15");
        assertThat(basket.summary().basketTotal()).isEqualByComparingTo("15");
        // ETA comes from the cached RedisDeliveryQuote, not a mock
        assertThat(basket.estimatedDeliveryTime()).isEqualTo("25–35 min");
    }

    @Test
    void skipsDeliveryEstimateWhenStoreHasBlockingStoreIssue() {
        RedisCartItem item = new RedisCartItem("i1", "p1", "s1", BigDecimal.ONE, null, null);

        RedisCart cart = new RedisCart("u1");
        // Quote is present on the basket but should not be shown — store is blocked
        cart.getStoreBaskets().add(basketWithQuote("s1", 25, 35, item));

        CartValidationResult validation = new CartValidationResult();
        validation.addStoreSnapshot(new StoreSnapshot("s1", "Store 1", null, true, true, null, null, null));
        validation.addStoreIssue("s1", new StoreIssue(StoreIssueCode.STORE_CLOSED, IssueSeverity.BLOCKING, "closed", "s1", Map.of()));
        validation.addProductSnapshot(new ProductSnapshot("p1", null, "s1", "Apple", "Brand", "Fruit", "img", ProductUnit.PCS,
                null, null, BigDecimal.ONE, BigDecimal.TEN, null, null, null, true, true));

        var response = builder.build(
                cart,
                new CartValidationContext(validation, Map.of(), null),
                new CartCalculationResult(Map.of(), Map.of())
        );

        var basket = response.storeBaskets().get(0);
        // available=false → estimate must be null regardless of quote
        assertThat(basket.estimatedDeliveryTime()).isNull();
        assertThat(basket.issues()).hasSize(1);
    }

    @Test
    void skipsDeliveryEstimateWhenNoQuotePresent() {
        RedisCartItem item = new RedisCartItem("i1", "p1", "s1", BigDecimal.ONE, null, null);

        RedisCart cart = new RedisCart("u1");
        // No address set → no quote on the basket
        cart.getStoreBaskets().add(basketWithoutQuote("s1", item));

        CartValidationResult validation = new CartValidationResult();
        validation.addStoreSnapshot(new StoreSnapshot("s1", "Store 1", null, true, true, null, null, null));
        validation.addProductSnapshot(new ProductSnapshot("p1", null, "s1", "Apple", "Brand", "Fruit", "img", ProductUnit.PCS,
                null, null, BigDecimal.ONE, BigDecimal.TEN, null, null, null, true, true));

        var response = builder.build(
                cart,
                new CartValidationContext(validation, Map.of(), null),
                new CartCalculationResult(Map.of(), Map.of())
        );

        var basket = response.storeBaskets().get(0);
        // Store is available, but no delivery quote exists yet (no address)
        assertThat(basket.estimatedDeliveryTime()).isNull();
    }

    @Test
    void usesDisplayLabelFromQuoteWhenPresent() {
        RedisCartItem item = new RedisCartItem("i1", "p1", "s1", BigDecimal.ONE, null, null);

        RedisStoreBasket storeBasket = new RedisStoreBasket("s1");
        storeBasket.setItems(new ArrayList<>(List.of(item)));
        storeBasket.setDeliveryQuote(new RedisDeliveryQuote(
                "q1", BigDecimal.valueOf(2), "TMT", "2 TMT",
                20, 30, "20–30 min",   // display label takes priority
                Instant.now().plusSeconds(300), Instant.now(), null
        ));

        RedisCart cart = new RedisCart("u1");
        cart.getStoreBaskets().add(storeBasket);

        CartValidationResult validation = new CartValidationResult();
        validation.addStoreSnapshot(new StoreSnapshot("s1", "Store 1", null, true, true, null, null, null));
        validation.addProductSnapshot(new ProductSnapshot("p1", null, "s1", "Apple", "Brand", "Fruit", "img", ProductUnit.PCS,
                null, null, BigDecimal.ONE, BigDecimal.TEN, null, null, null, true, true));

        var response = builder.build(
                cart,
                new CartValidationContext(validation, Map.of(), null),
                new CartCalculationResult(Map.of(), Map.of())
        );

        assertThat(response.storeBaskets().get(0).estimatedDeliveryTime()).isEqualTo("20–30 min");
    }
}
