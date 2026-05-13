package com.sebet.cartservice.cart.product.projection.event;

import com.sebet.cartservice.cart.enums.ProductUnit;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductEventData(
        String productId,
        String storeId,

        String sku,
        String name,
        String brandName,
        String categoryId,
        String categoryName,
        String imageUrl,

        ProductUnit unit,

        BigDecimal minQuantity,
        BigDecimal maxQuantity,
        BigDecimal quantityStep,

        BigDecimal unitPrice,
        BigDecimal originalUnitPrice,

        Boolean active,
        Boolean sellable,
        Boolean deleted,

        Long productVersion,
        Long priceVersion,

        Instant productUpdatedAt,
        Instant priceUpdatedAt,

        String reason
) {
}
