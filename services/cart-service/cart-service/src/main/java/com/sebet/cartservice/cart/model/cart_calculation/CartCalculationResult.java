package com.sebet.cartservice.cart.model.cart_calculation;

import java.util.Map;

public record CartCalculationResult(

//
//        /**
//         * Item-level calculations.
//         *
//         * Key = cartItemId
//         */
        Map<String, ItemCalculation> itemCalculationsByCartItemId,

//        /**
//         * Store basket-level calculations.
//         *
//         * Key = storeId
//         */
        Map<String, StoreBasketCalculation> storeBasketCalculationsByStoreId,

//        /**
//         * Whole cart calculation summary.
//         */
        CartTotalCalculation cartTotal

) {
}
