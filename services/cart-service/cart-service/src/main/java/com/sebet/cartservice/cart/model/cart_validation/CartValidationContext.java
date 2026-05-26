package com.sebet.cartservice.cart.model.cart_validation;

import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.dto.PromotionEligibleItemData;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromotionEvaluationResponse;

import java.util.Map;

public record CartValidationContext(
        CartValidationResult validationResult,
        Map<String, PromotionEligibleItemData> promotionItemData,
        PromotionEvaluationResponse promotionEvaluationResponse
) {
}
