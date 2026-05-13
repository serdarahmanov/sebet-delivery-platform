package com.sebet.cartservice.cart.model;

import com.sebet.cartservice.cart.enums.ProductUnavailableReason;

import java.math.BigDecimal;

public record CartItemIssueMetadata(
        BigDecimal number,
        BigDecimal newPrice,
        BigDecimal requestedQuantity,
        BigDecimal availableQuantity,
        ProductUnavailableReason reason
) {
}
