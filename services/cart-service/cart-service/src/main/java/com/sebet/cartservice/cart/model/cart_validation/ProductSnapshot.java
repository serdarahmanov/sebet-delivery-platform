package com.sebet.cartservice.cart.model.cart_validation;

import com.sebet.cartservice.cart.enums.ProductUnit;
import com.sebet.cartservice.cart.enums.StockStatus;

import java.math.BigDecimal;

public record ProductSnapshot(

        String productId,
        String sku,

        String storeId,

        String name,
        String brandName,
        String categoryName,
        String imageUrl,

        ProductUnit unit,

        BigDecimal minQuantity,
        BigDecimal maxQuantity,
        BigDecimal quantityStep,

        BigDecimal currentPrice,
        BigDecimal originalPrice,

        BigDecimal availableQuantity,
        StockStatus stockStatus,

        boolean exists,
        boolean available
) {
}
