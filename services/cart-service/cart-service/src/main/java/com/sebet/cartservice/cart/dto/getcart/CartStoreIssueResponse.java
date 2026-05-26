package com.sebet.cartservice.cart.dto.getcart;

import java.util.Map;

public record CartStoreIssueResponse(
        String code,
        String severity,
        String scope,
        String message,
        Map<String, Object> metadata
) {
}
