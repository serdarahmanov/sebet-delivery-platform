package com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response;

import com.sebet.cartservice.cart.enums.ProductUnavailableReason;

import java.math.BigDecimal;

public record PromotionDiscount(
        String promotionId,
        String name,

        PromotionDiscountTarget target,
        PromoCodeType type,

        BigDecimal discountAmount
) {
}
