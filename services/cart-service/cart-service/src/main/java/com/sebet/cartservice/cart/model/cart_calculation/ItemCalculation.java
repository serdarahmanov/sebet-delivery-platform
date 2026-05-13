package com.sebet.cartservice.cart.model.cart_calculation;

import java.math.BigDecimal;

public record ItemCalculation(
        String cartItemId,
        String productId,
        String storeId,

        BigDecimal unitPrice,
        BigDecimal quantity,

        BigDecimal subtotal,

        BigDecimal itemDiscountTotal,

        BigDecimal finalTotal
) {
}
