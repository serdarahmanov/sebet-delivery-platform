package com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response;

import java.util.List;

public record PromotionItemDiscountResult(
        String cartItemId,
        String productId,
        List<PromotionDiscount> discounts) {
}
