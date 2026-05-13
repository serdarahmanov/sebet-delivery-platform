package com.sebet.cartservice.cart.promotion.client;

import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.request.PromotionEvaluationRequest;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response.PromotionEvaluationResponse;

public interface PromotionClient {
    PromotionEvaluationResponse evaluatePromotions(PromotionEvaluationRequest request);
}
