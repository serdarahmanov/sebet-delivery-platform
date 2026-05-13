package com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.request;

import java.math.BigDecimal;
import java.util.List;

public record PromotionStoreBasketRequest(
        String storeId,
        List<String> promoCodes,
        BigDecimal itemsSubtotal,
        BigDecimal deliveryFee,
        List<PromotionCartItemRequest> items
) {
}
