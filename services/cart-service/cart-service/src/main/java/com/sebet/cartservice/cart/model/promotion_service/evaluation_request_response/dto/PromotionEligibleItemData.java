package com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.dto;

import com.sebet.cartservice.cart.enums.ProductUnit;

import java.math.BigDecimal;

public record PromotionEligibleItemData(
        String cartItemId,
        String productId,
        String storeId,
        String categoryId,

        BigDecimal quantity,
        ProductUnit unit,

        BigDecimal unitPrice,
        BigDecimal lineSubtotal
) {
}
