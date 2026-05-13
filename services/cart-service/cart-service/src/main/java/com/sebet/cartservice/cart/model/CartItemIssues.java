package com.sebet.cartservice.cart.model;

import com.sebet.cartservice.cart.enums.CartItemIssuesCode;
import com.sebet.cartservice.cart.enums.IssueScope;
import com.sebet.cartservice.cart.enums.IssueSeverity;

public record CartItemIssues(
        CartItemIssuesCode code,
        IssueSeverity severity,
        IssueScope scope,
        String message,
        CartItemIssueMetadata metadata
) {
}
