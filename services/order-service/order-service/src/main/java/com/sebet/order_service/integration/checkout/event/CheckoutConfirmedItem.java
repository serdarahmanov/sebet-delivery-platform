package com.sebet.order_service.integration.checkout.event;

import com.sebet.order_service.shared.enums.ProductUnit;

import java.math.BigDecimal;
import java.util.Objects;

public record CheckoutConfirmedItem(
        String productId,
        String productName,
        BigDecimal quantity,
        ProductUnit unit,
        BigDecimal unitPriceAmount,
        BigDecimal grossAmount,
        BigDecimal discountAmount,
        BigDecimal netAmount,
        String imageUrl
) {

    public CheckoutConfirmedItem {
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(productName, "productName must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(unitPriceAmount, "unitPriceAmount must not be null");
        Objects.requireNonNull(grossAmount, "grossAmount must not be null");
        Objects.requireNonNull(discountAmount, "discountAmount must not be null");
        Objects.requireNonNull(netAmount, "netAmount must not be null");
    }
}
