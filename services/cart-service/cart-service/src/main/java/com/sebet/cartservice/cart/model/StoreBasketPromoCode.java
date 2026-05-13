package com.sebet.cartservice.cart.model;

import com.sebet.cartservice.cart.enums.ProductUnavailableReason;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.issue.PromoIssue;


import java.math.BigDecimal;
import java.util.List;

public record StoreBasketPromoCode(
        String code,
        boolean applied,
        ProductUnavailableReason.PromoCodeType type,
        BigDecimal discountTotal,
        String description,
        List<PromoIssue> issues
) {
}
