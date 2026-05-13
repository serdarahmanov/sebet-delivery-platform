package com.sebet.cartservice.cart.model;

import java.time.Instant;
import java.util.List;

public record CartStoreBasket(
        String basketId,
        String storeId,
        String storeName,
        Boolean isAvailable,
        Boolean canCheckout,

        CartDeliveryAddress deliveryAddress,

        StoreBasketSummary summary,

        StoreBasketPromoCode promoCode,

        List<StoreBasketIssue> issues,
        List<CartItem> items,
        Instant createdAt,
        Instant updatedAt
) {
}
