package com.sebet.cartservice.cart.model.cart_validation;

import com.sebet.cartservice.cart.enums.ProductUnavailableReason;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.issue.PromoIssue;

import java.math.BigDecimal;
import java.util.List;

public record PromoCodeValidationResult(
        String storeId,
        String code,

        boolean exists,
        boolean valid,
        boolean applied,

        ProductUnavailableReason.PromoCodeType type,

        BigDecimal discountValue,
        BigDecimal minimumBasketAmount,

        String description,

        List<PromoIssue> issues
) {
}
