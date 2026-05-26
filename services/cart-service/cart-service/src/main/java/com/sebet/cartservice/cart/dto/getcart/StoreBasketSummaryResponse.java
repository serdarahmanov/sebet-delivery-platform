package com.sebet.cartservice.cart.dto.getcart;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public record StoreBasketSummaryResponse(
        String basketId,
        String storeId,
        String storeName,
        Boolean isAvailable,
        Boolean canCheckout,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String estimatedDeliveryTime,
        CartBasketSummaryResponse summary,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<CartAppliedPromoCodeResponse> appliedPromoCodes,
        List<CartStoreIssueResponse> issues,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<CartStoreBasketIssueResponse> basketIssues,
        List<CartItemResponse> items,
        Instant createdAt,
        Instant updatedAt
) {
}
