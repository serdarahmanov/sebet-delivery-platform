package com.sebet.cartservice.cart.model;

import com.sebet.cartservice.cart.enums.ItemDiscountType;

import java.math.BigDecimal;

public record CartItemDiscount(
        ItemDiscountType type,
        String title,
        BigDecimal amount,
        BigDecimal originalUnitPrice,
        BigDecimal discountedUnitPrice,
        BigDecimal percentage,
        BigDecimal quantityAffected
) {
}
