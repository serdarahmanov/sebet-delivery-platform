package com.sebet.cartservice.cart.model;

import com.sebet.cartservice.cart.enums.store_basket_issues.StoreBasketIssueReason;

import java.math.BigDecimal;

public record StoreBasketIssueMetadata(
        String storeId,
        BigDecimal minimumOrderAmount,
        BigDecimal currentBasketTotal,
        String addressId,
        BigDecimal latitude,
        BigDecimal longitude,
        String promoCode,

        StoreBasketIssueReason reasons,
        Integer retryAfterMinutes
) {
}
