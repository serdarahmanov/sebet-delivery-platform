package com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response;

import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.issue.PromoIssue;

import java.math.BigDecimal;
import java.util.List;

public record PromotionStoreBasketResult(
        String storeId,

        List<AppliedPromoCodeResult> promoCodeResults,
        List<PromotionItemDiscountResult> itemDiscounts,
        List<PromotionDiscount> deliveryDiscounts,

        BigDecimal itemDiscountTotal,
        BigDecimal basketDiscountTotal,
        BigDecimal deliveryDiscountTotal,

        List<PromoIssue> issues
) {
}
