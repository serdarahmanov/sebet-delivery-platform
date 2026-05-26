package com.sebet.cartservice.cart.model;

import java.math.BigDecimal;

public record StoreBasketSummary(
        BigDecimal itemsCount,
        int uniqueItemsCount,

        BigDecimal itemsSubtotal,

        BigDecimal itemDiscountTotal,
        BigDecimal promoCodeDiscountTotal,
        BigDecimal deliveryDiscountTotal,
        BigDecimal totalSavings,

        StoreBasketDeliverySummary delivery,

        BigDecimal serviceFee,

        BigDecimal grandTotal

) {
}
