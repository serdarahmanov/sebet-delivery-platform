package com.sebet.cartservice.cart.dto.getcart;

import java.math.BigDecimal;

public record CartBasketSummaryResponse(
        BigDecimal itemsCount,
        BigDecimal itemsSubtotalBeforeDiscount,
        BigDecimal itemsSubtotalAfterDiscount,
        BigDecimal basketTotal
) {
}
