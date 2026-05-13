package com.sebet.cartservice.cart.model.cart_calculation;

import java.math.BigDecimal;

public record CartTotalCalculation(
        BigDecimal itemsSubtotal,

        BigDecimal itemDiscountTotal,
        BigDecimal promoDiscountTotal,
        BigDecimal totalDiscount,

        BigDecimal deliveryFeeTotal,
        BigDecimal freeDeliveryDiscountTotal,

        BigDecimal serviceFeeTotal,
        BigDecimal smallOrderFeeTotal,

        BigDecimal grandTotal
) {
}
