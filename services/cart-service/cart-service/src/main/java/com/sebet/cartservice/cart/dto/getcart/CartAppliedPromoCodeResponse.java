package com.sebet.cartservice.cart.dto.getcart;

import java.math.BigDecimal;

public record CartAppliedPromoCodeResponse(
        String code,
        String title,
        String promotionId,
        String type,
        BigDecimal discountAmount
) {
}
