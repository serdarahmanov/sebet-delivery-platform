package com.sebet.cartservice.cart.validation.tools;

import com.sebet.cartservice.cart.model.cart_validation.CartValidationResult;
import com.sebet.cartservice.cart.model.promotion_service.evaluation_request_response.dto.PromotionEligibleItemData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class CartValidationAccumulator {

    private final CartValidationResult validationResult = new CartValidationResult();

    private final Map<String, PromotionEligibleItemData> promotionItemData = new HashMap<>();

    public CartValidationResult validationResult() {
        return validationResult;
    }

    public void putPromotionItemData(PromotionEligibleItemData data) {
        if (data == null || data.cartItemId() == null) {
            return;
        }

        promotionItemData.put(data.cartItemId(), data);
    }

    public PromotionEligibleItemData getPromotionItemData(String cartItemId) {
        return promotionItemData.get(cartItemId);
    }

    public List<PromotionEligibleItemData> getAllPromotionItemData() {
        return List.copyOf(promotionItemData.values());
    }

    public Map<String, PromotionEligibleItemData> getPromotionItemDataMap() {
        return Map.copyOf(promotionItemData);
    }
}
