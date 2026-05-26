package com.sebet.cartservice.cart.model.cart_calculation;

import com.sebet.cartservice.cart.enums.FreeDeliveryReason;

import java.math.BigDecimal;

public record StoreBasketCalculation(
        String storeId,

        BigDecimal itemsCount,
        int uniqueItemsCount,

        BigDecimal itemsSubtotal,

        BigDecimal itemDiscountTotal,
        BigDecimal promoDiscountTotal,
        BigDecimal totalDiscount,

        BigDecimal deliveryFee,
        BigDecimal freeDeliveryDiscount,
        FreeDeliveryReason freeDeliveryReason,
        BigDecimal basketTotal,
        BigDecimal serviceFee,
        BigDecimal amountToFreeDelivery
) {
}