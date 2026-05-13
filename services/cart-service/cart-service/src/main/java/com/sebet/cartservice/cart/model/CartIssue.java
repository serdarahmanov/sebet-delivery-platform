package com.sebet.cartservice.cart.model;

import com.sebet.cartservice.cart.enums.IssueScope;
import com.sebet.cartservice.cart.enums.IssueSeverity;
import com.sebet.cartservice.cart.enums.cart_issue.CartIssueCode;

public record CartIssue(
        CartIssueCode code,
        IssueSeverity severity,
        IssueScope scope,
        String message,
        String cartId
) {
}
