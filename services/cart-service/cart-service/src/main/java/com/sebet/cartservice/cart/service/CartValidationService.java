package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.enums.CartItemIssuesCode;
import com.sebet.cartservice.cart.enums.IssueScope;
import com.sebet.cartservice.cart.enums.IssueSeverity;
import com.sebet.cartservice.cart.enums.ProductUnavailableReason;
import com.sebet.cartservice.cart.enums.cart_issue.CartIssueCode;
import com.sebet.cartservice.cart.enums.store_basket_issues.StoreBasketIssueCode;
import com.sebet.cartservice.cart.enums.store_basket_issues.StoreBasketIssueScope;
import com.sebet.cartservice.cart.model.CartIssue;
import com.sebet.cartservice.cart.model.CartItemIssueMetadata;
import com.sebet.cartservice.cart.model.CartItemIssues;
import com.sebet.cartservice.cart.model.StoreBasketIssue;
import com.sebet.cartservice.cart.model.StoreBasketIssueMetadata;
import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.ProductSnapshot;
import com.sebet.cartservice.cart.model.cart_validation.PromoCodeValidationResult;
import com.sebet.cartservice.cart.model.cart_validation.StoreSnapshot;
import com.sebet.cartservice.cart.model.redis.RedisCart;
import com.sebet.cartservice.cart.model.redis.RedisCartItem;
import com.sebet.cartservice.cart.model.redis.RedisCartPromoCode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;


@Service
public class CartValidationService {


    public CartValidationResult validate(RedisCart redisCart) {

        List<RedisCartItem> items = safeItems(redisCart);

        Map<String, ProductSnapshot> productsByProductId =
                fetchProductSnapshots(items);

        Map<String, StoreSnapshot> storesByStoreId =
                fetchStoreSnapshots(items);

        Map<String, List<CartItemIssues>> itemIssuesByCartItemId =
                validateItems(items, productsByProductId);

        Map<String, List<StoreBasketIssue>> storeBasketIssuesByStoreId =
                validateStoreBaskets(redisCart, storesByStoreId);

        Map<String, PromoCodeValidationResult> promoResultsByStoreId =
                validatePromoCodes(redisCart, productsByProductId, storesByStoreId);

        List<CartIssue> cartIssues =
                validateCart(redisCart);

        return new CartValidationResult(
                productsByProductId,
                storesByStoreId,
                itemIssuesByCartItemId,
                storeBasketIssuesByStoreId,
                promoResultsByStoreId,
                cartIssues
        );
    }

    private List<RedisCartItem> safeItems(RedisCart redisCart) {
        if (redisCart == null || redisCart.getItems() == null) {
            return List.of();
        }

        return redisCart.getItems();
    }






    private Map<String, ProductSnapshot> fetchProductSnapshots(List<RedisCartItem> items) {

        List<String> productIds = items.stream()
                .map(RedisCartItem::getProductId)
                .distinct()
                .toList();

        if (productIds.isEmpty()) {
            return Map.of();
        }

        // Later:



        // return productClient.getProductSnapshots(productIds);
//        Does product still exist?
//        Is product available?
//                What is current price?
//                How much stock is available?
//        What is product name?
//        What is product image?


        return Map.of();
    }




    private Map<String, StoreSnapshot> fetchStoreSnapshots(List<RedisCartItem> items) {
        List<String> storeIds = items.stream()
                .map(RedisCartItem::getStoreId)
                .distinct()
                .toList();

        if (storeIds.isEmpty()) {
            return Map.of();
        }

        // Later:
        // return storeClient.getStoreSnapshots(storeIds);

//        Does store still exist?
//        Is store open?
//                Is store accepting orders?
//                What is minimum order amount?
//        What is delivery fee?
//        What is free delivery threshold?

        return Map.of();
    }




    private Map<String, List<CartItemIssues>> validateItems(
            List<RedisCartItem> items,
            Map<String, ProductSnapshot> productsByProductId
    ) {
        Map<String, List<CartItemIssues>> result = new java.util.HashMap<>();

        for (RedisCartItem item : items) {
            List<CartItemIssues> issues = new java.util.ArrayList<>();

            ProductSnapshot product = productsByProductId.get(item.getProductId());

            if (product == null || !product.exists()) {
                issues.add(new CartItemIssues(
                        CartItemIssuesCode.PRODUCT_NOT_FOUND,
                        IssueSeverity.BLOCKING,
                        IssueScope.ITEM,
                        "This product is no longer available",
                        new CartItemIssueMetadata(
                                null,
                                null,
                                null,
                                null,
                                null
                        )
                ));

                result.put(item.getCartItemId(), issues);
                continue;
            }

            if (!product.available()) {
                issues.add(new CartItemIssues(
                        CartItemIssuesCode.PRODUCT_UNAVAILABLE,
                        IssueSeverity.BLOCKING,
                        IssueScope.ITEM,
                        product.name() + " is currently unavailable",
                        new CartItemIssueMetadata(
                                null,
                                null,
                                null,
                                null,
                                null
                        )
                ));
            }

            if (item.getQuantity().compareTo(product.availableQuantity()) > 0) {
                issues.add(new CartItemIssues(
                        CartItemIssuesCode.INSUFFICIENT_STOCK,
                        IssueSeverity.BLOCKING,
                        IssueScope.ITEM,
                        "Only " + product.availableQuantity() + " available",
                        new CartItemIssueMetadata(
                                null,
                                null,
                                item.getQuantity(),
                                product.availableQuantity(),
                                null
                        )
                ));
            }

            if (item.getUnitPriceSnapshot() != null
                    && product.currentPrice() != null
                    && item.getUnitPriceSnapshot().compareTo(product.currentPrice()) != 0) {
                issues.add(new CartItemIssues(
                        CartItemIssuesCode.PRICE_CHANGED,
                        IssueSeverity.WARNING,
                        IssueScope.ITEM,
                        product.name() + " price changed",
                        new CartItemIssueMetadata(
                                item.getUnitPriceSnapshot(),
                                product.currentPrice(),
                                null,
                                null,
                                null
                        )
                ));
            }

            result.put(item.getCartItemId(), issues);
        }

        return result;




    }







    private Map<String, List<StoreBasketIssue>> validateStoreBaskets(
            RedisCart redisCart,
            Map<String, StoreSnapshot> storesByStoreId
    ) {


        List<RedisCartItem> items = safeItems(redisCart);

        Map<String, List<StoreBasketIssue>> result = new java.util.HashMap<>();

        List<String> storeIds = items.stream()
                .map(RedisCartItem::getStoreId)
                .distinct()
                .toList();

        for (String storeId : storeIds) {
            List<StoreBasketIssue> issues = new java.util.ArrayList<>();

            StoreSnapshot store = storesByStoreId.get(storeId);

            if (store == null || !store.exists()) {
                issues.add(new StoreBasketIssue(
                        StoreBasketIssueCode.STORE_NOT_FOUND,
                        IssueSeverity.BLOCKING,
                        StoreBasketIssueScope.BASKET,
                        "This store is no longer available",
                        new StoreBasketIssueMetadata(
                                storeId,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        )
                ));

                result.put(storeId, issues);
                continue;
            }

            if (!store.open()) {
                issues.add(new StoreBasketIssue(
                        StoreBasketIssueCode.STORE_CLOSED,
                        IssueSeverity.BLOCKING,
                        StoreBasketIssueScope.BASKET,
                        store.name() + " is currently closed",
                        new StoreBasketIssueMetadata(
                                storeId,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        )
                ));
            }

            result.put(storeId, issues);
        }

        return result;

    }









    private Map<String, PromoCodeValidationResult> validatePromoCodes(
            RedisCart redisCart,
            Map<String, ProductSnapshot> productsByProductId,
            Map<String, StoreSnapshot> storesByStoreId
    ) {

//        it should check if:
//
//        Does promo code exist?
//        Is it active?
//                Is it expired?
//        Is it for this store?
//                Has user already used it?
//        Can it apply to these items?
//        Is minimum basket amount reached?

        if (redisCart == null || redisCart.getPromoCodes() == null) {
            return Map.of();
        }

        Map<String, PromoCodeValidationResult> result = new java.util.HashMap<>();

        for (RedisCartPromoCode redisPromoCode : redisCart.getPromoCodes()) {

            PromoCodeValidationResult promoResult =
                    validateSinglePromoCode(
                            redisCart,
                            redisPromoCode,
                            productsByProductId,
                            storesByStoreId
                    );

            result.put(redisPromoCode.getStoreId(), promoResult);
        }

        return result;
    }



    private PromoCodeValidationResult validateSinglePromoCode(
            RedisCart redisCart,
            RedisCartPromoCode redisPromoCode,
            Map<String, ProductSnapshot> productsByProductId,
            Map<String, StoreSnapshot> storesByStoreId
    ) {
        // Later this will call Promo Service.
        // For now we return a temporary valid result.

        return new PromoCodeValidationResult(
                redisPromoCode.getStoreId(),
                redisPromoCode.getCode(),
                true,
                true,
                true,
                ProductUnavailableReason.PromoCodeType.PERCENTAGE,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                "10% discount",
                List.of()
        );
    }
















    private List<CartIssue> validateCart(RedisCart redisCart) {

        List<CartIssue> issues = new java.util.ArrayList<>();

        if (redisCart == null) {
            issues.add(new CartIssue(
                    CartIssueCode.CART_NOT_FOUND,
                    IssueSeverity.BLOCKING,
                    IssueScope.CART,
                    "Your cart does not exist or has expired",
                    null
            ));

            return issues;
        }

        List<RedisCartItem> items = safeItems(redisCart);

        if (items.isEmpty() ) {
            issues.add(new CartIssue(
                    CartIssueCode.CART_EMPTY,
                    IssueSeverity.BLOCKING,
                    IssueScope.CART,
                    "Your cart is empty",
                    redisCart.getCartId()
            ));
        }

        long storeCount = items.stream()
                .map(RedisCartItem::getStoreId)
                .distinct()
                .count();

        if (storeCount > 4) {
            issues.add(new CartIssue(
                    CartIssueCode.TOO_MANY_STORES,
                    IssueSeverity.BLOCKING,
                    IssueScope.CART,
                    "You can order from maximum 4 stores at once",
                    redisCart.getCartId()
            ));
        }

        return issues;


    }
}