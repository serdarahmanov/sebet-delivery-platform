package com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.request;

import java.time.Instant;
import java.util.List;

public record PromotionEvaluationRequest(
        String cartId,
        String userId,
        String currency,
        Instant requestedAt,
        List<PromotionStoreBasketRequest> storeBaskets
) {
    public static PromotionEvaluationRequest empty(String cartId, String userId) {
        return new PromotionEvaluationRequest(
                cartId,
                userId,
                "TMT",
                Instant.now(),
                List.of()
        );
    }
}


//Example Request

//{
//        "cartId": "cart_123",
//        "userId": "user_456",
//        "currency": "TMT",
//        "requestedAt": "2026-05-12T10:30:00Z",
//        "storeBaskets": [
//        {
//        "storeId": "store_001",
//        "promoCodes": ["WELCOME10"],
//        "itemsSubtotal": 55.00,
//        "deliveryFee": 20.00,
//        "items": [
//        {
//        "productId": "prod_apple_001",
//        "categoryId": "cat_fruits",
//        "quantity": 2,
//        "unit": "PIECE",
//        "unitPrice": 20.00,
//        "lineSubtotal": 40.00
//        },
//        {
//        "productId": "prod_water_001",
//        "categoryId": "cat_drinks",
//        "quantity": 1,
//        "unit": "PIECE",
//        "unitPrice": 15.00,
//        "lineSubtotal": 15.00
//        }
//        ]
//        }
//        ]
//        }