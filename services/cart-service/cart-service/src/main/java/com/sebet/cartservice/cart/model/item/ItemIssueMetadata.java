package com.sebet.cartservice.cart.model.item;

import com.sebet.cartservice.cart.enums.ProductUnavailableReason;

import java.math.BigDecimal;

public record ItemIssueMetadata(
        BigDecimal number,
        BigDecimal newPrice,
        BigDecimal requestedQuantity,
        BigDecimal availableQuantity,
        ProductUnavailableReason reason
) {
}
