package com.sebet.cartservice.cart.model;

import com.sebet.cartservice.cart.enums.IssueSeverity;
import com.sebet.cartservice.cart.enums.store_basket_issues.StoreBasketIssueCode;
import com.sebet.cartservice.cart.enums.store_basket_issues.StoreBasketIssueScope;

public record StoreBasketIssue(
        StoreBasketIssueCode code,
        IssueSeverity severity,
        StoreBasketIssueScope scope,
        String message,
        StoreBasketIssueMetadata metadata
) {
}
