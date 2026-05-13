package com.sebet.cartservice.cart.model.cart_calculation;

import java.math.BigDecimal;

public record StoreBasketCalculation(
        String storeId,

        BigDecimal itemsCount,
        BigDecimal uniqueItemsCount,


        BigDecimal itemsSubtotal,

        BigDecimal itemDiscountTotal,
        BigDecimal promoDiscountTotal,
        BigDecimal totalDiscount,

        BigDecimal deliveryFee,
        BigDecimal freeDeliveryDiscount,
        BigDecimal basketTotal,
        BigDecimal serviceFee,
        BigDecimal smallOrderFee,
        BigDecimal amountToFreeDelivery
) {
}