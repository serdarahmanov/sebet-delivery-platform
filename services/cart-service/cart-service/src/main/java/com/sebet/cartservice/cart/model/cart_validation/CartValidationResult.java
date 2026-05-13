package com.sebet.cartservice.cart.model.cart_validation;

import com.sebet.cartservice.cart.model.CartIssue;
import com.sebet.cartservice.cart.model.CartItemIssues;
import com.sebet.cartservice.cart.model.StoreBasketIssue;

import java.util.List;
import java.util.Map;

public record CartValidationResult(


        /**
         * Current product information fetched from Product/Catalog service.
         *
         * Key = productId
         *
         * Example:
         * "product_001" -> ProductSnapshot(...)
         */
        Map<String, ProductSnapshot> productsByProductId,

        /**
         * Current store information fetched from Store service.
         *
         * Key = storeId
         *
         * Example:
         * "store_001" -> StoreSnapshot(...)
         */
        Map<String, StoreSnapshot> storesByStoreId,

        /**
         * Item-level issues.
         *
         * Key = cartItemId
         *
         * Example:
         * "cart_item_001" -> [
         *      PRICE_CHANGED,
         *      INSUFFICIENT_STOCK
         * ]
         */
        Map<String, List<CartItemIssues>> itemIssuesByCartItemId,

        /**
         * Store basket-level issues.
         *
         * Key = storeId
         *
         * Example:
         * "store_001" -> [
         *      STORE_CLOSED,
         *      MINIMUM_ORDER_NOT_REACHED
         * ]
         */
        Map<String, List<StoreBasketIssue>> storeBasketIssuesByStoreId,

        /**
         * Promo code validation result per store basket.
         *
         * Key = storeId
         *
         * Example:
         * "store_001" -> PromoCodeValidationResult(...)
         */
        Map<String, PromoCodeValidationResult> promoResultsByStoreId,

        /**
         * Whole-cart issues.
         *
         * These are not connected to one item or one store.
         *
         * Example:
         * TOO_MANY_STORES
         * CART_EMPTY
         * DELIVERY_ADDRESS_MISSING
         */
        List<CartIssue> cartIssues
) {
}
