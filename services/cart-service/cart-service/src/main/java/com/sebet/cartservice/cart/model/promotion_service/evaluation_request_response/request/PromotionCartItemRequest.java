package com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.request;

import com.sebet.cartservice.cart.enums.ProductUnit;

import java.math.BigDecimal;

public record PromotionCartItemRequest(
        String cartItemId,
        String productId,
        String categoryId,

        BigDecimal quantity,
        ProductUnit unit,

        BigDecimal unitPrice,
        BigDecimal lineSubtotal
) {
}
