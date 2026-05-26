package com.sebet.cartservice.cart.model;

import com.sebet.cartservice.cart.enums.ScheduleType;
import com.sebet.cartservice.cart.model.store.StoreIssue;

import java.time.Instant;
import java.util.List;

public record StoreBasket(
        String basketId,
        String storeId,
        String storeName,
        Boolean isAvailable,
        Boolean canCheckout,

        String addressId,
        CartDeliveryQuote deliveryQuote,

        String selectedDeliveryMethodId,
        List<CartDeliveryOption> availableDeliveryOptions,

        ScheduleType scheduleType,
        Instant scheduledFor,

        StoreBasketSummary summary,

        List<CartPromoCodeResponse> promoCodes,

        List<StoreBasketIssue> issues,
        List<StoreIssue> storeIssues,
        List<CartItem> items,
        Instant createdAt,
        Instant updatedAt
) {
}
