package com.sebet.cartservice.cart.model.cart_calculation;

import java.util.Map;

public record CartCalculationResult(
        Map<String, ItemCalculation> itemCalculationsByCartItemId,
        Map<String, StoreBasketCalculation> storeBasketCalculationsByStoreId
) {
}
