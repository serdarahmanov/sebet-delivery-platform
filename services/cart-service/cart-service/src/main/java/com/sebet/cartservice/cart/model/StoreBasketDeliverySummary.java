package com.sebet.cartservice.cart.model;

import com.sebet.cartservice.cart.enums.FreeDeliveryReason;

import java.math.BigDecimal;

public record StoreBasketDeliverySummary(
        BigDecimal deliveryFeeBeforeDiscount,
        BigDecimal deliveryFee,
        BigDecimal deliveryDiscountTotal,
        FreeDeliveryReason freeDelivery

) {
}
