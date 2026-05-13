package com.sebet.cartservice.cart.model;

import com.sebet.cartservice.cart.enums.ProductUnit;
import com.sebet.cartservice.cart.enums.StockStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CartItem(
        String cartItemId,
        String productId,
        String sku,

        String storeId,

        String name,
        String brandName,
        String categoryName,
        String imgUrl,

        BigDecimal quantity,

        ProductUnit unit,
        BigDecimal minQuantity,
        BigDecimal maxQuantity,
        BigDecimal quantityStep,

        BigDecimal unitPrice,
        BigDecimal originalUnitPrice,

        BigDecimal lineSubtotal,
        BigDecimal lineDiscountTotal,
        BigDecimal lineTotal,

        List<CartItemDiscount> discounts,

        Boolean available,
        Integer stockAvailable,
        StockStatus stockStatus,

        Boolean isValid,
        List<CartItemIssues> issues,

        Instant addedAt,
        Instant updatedAt


) {
}
