package com.sebet.cartservice.cart.model.item;

import com.sebet.cartservice.cart.enums.ItemIssuesCode;
import com.sebet.cartservice.cart.enums.IssueScope;
import com.sebet.cartservice.cart.enums.IssueSeverity;

import java.util.Map;

public record ItemIssue(
        ItemIssuesCode code,
        IssueSeverity severity,
        IssueScope scope,
        String message,
        Map<String, Object> metadata
) {
    public boolean isBlocking() {
        return severity == IssueSeverity.BLOCKING;
    }
}
