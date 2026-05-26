package com.sebet.cartservice.cart.model;

import com.sebet.cartservice.cart.enums.IssueSeverity;
import com.sebet.cartservice.cart.enums.store_basket_issues.StoreBasketIssueCode;
import com.sebet.cartservice.cart.enums.store_basket_issues.StoreBasketIssueScope;

import java.math.BigDecimal;
import java.util.Map;

public record StoreBasketIssue(
        StoreBasketIssueCode code,
        IssueSeverity severity,
        StoreBasketIssueScope scope,
        String message,
        String storeId,
        BigDecimal minimumOrderAmount,
        BigDecimal currentBasketTotal,
        Map<String, Object>metadata

) {
    public boolean isBlocking() {
        return severity == IssueSeverity.BLOCKING;
    }
}
