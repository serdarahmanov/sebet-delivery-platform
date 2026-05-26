package com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.response;

import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.request.PromotionEvaluationRequest;

import java.time.Instant;
import java.util.List;

public record PromotionEvaluationResponse(
        String cartId,
        List<PromotionStoreBasketResult> storeBasketResults,
        boolean degraded,
        DegradedReason degradedReason
) {
    public static PromotionEvaluationResponse empty(String cartId) {
        return new PromotionEvaluationResponse(cartId, List.of(), false, null);
    }

    public static PromotionEvaluationResponse degraded(String cartId, DegradedReason reason) {
        return new PromotionEvaluationResponse(cartId, List.of(), true, reason);
    }
}


//Example Response where:
//WELCOME10 = basket-level 10% discount
//Water = automatic item-level 10% discount


//{
//        "cartId": "cart_123",
//        "storeBasketResults": [
//        {
//        "storeId": "store_001",
//
//        "promoCodeResults": [
//        {
//        "code": "WELCOME10",
//        "applied": true,
//        "promotionId": "promo_welcome10",
//        "type": "PERCENTAGE",
//        "discountAmount": 5.35,
//        "message": "WELCOME10 applied successfully",
//        "issues": []
//        }
//        ],
//
//        "itemDiscounts": [
//        {
//        "productId": "prod_water_001",
//        "discounts": [
//        {
//        "promotionId": "promo_water_10_percent",
//        "name": "10% off water",
//        "target": "ITEM",
//        "type": "PERCENTAGE",
//        "discountAmount": 1.50
//        }
//        ]
//        }
//        ],
//
//        "basketDiscounts": [
//        {
//        "promotionId": "promo_welcome10",
//        "name": "WELCOME10",
//        "target": "STORE_BASKET",
//        "type": "PERCENTAGE",
//        "discountAmount": 5.35
//        }
//        ],
//
//        "deliveryDiscounts": [],
//
//        "itemDiscountTotal": 1.50,
//        "basketDiscountTotal": 5.35,
//        "deliveryDiscountTotal": 0.00,
//
//        "issues": []
//        }
//        ]
//        }