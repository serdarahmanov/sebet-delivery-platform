package com.sebet.cartservice.cart.dto.getcart;

import java.time.Instant;
import java.util.List;

public record CartSummaryResponse(
        String cartId,
        List<StoreBasketSummaryResponse> storeBaskets,
        Integer totalBasketsCount,
        List<CartIssueResponse> cartIssues,
        Instant createdAt,
        Instant updatedAt
) {
}
