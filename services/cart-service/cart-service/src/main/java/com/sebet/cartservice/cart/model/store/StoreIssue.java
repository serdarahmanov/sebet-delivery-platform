package com.sebet.cartservice.cart.model.store;

import com.sebet.cartservice.cart.enums.IssueSeverity;

import java.util.Map;

public record StoreIssue(
        StoreIssueCode code,
        IssueSeverity severity,
        String message,
        String storeId,
        Map<String, Object> metadata

) {
    public boolean isBlocking() {
        return severity == IssueSeverity.BLOCKING;
    }
}
