package com.sebet.cartservice.cart.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record BatchCartItemRequest(
        @NotBlank(message = "Store id is required")
        String storeId,
        @NotBlank(message = "Product id is required")
        String productId,
        @NotNull(message = "Quantity is required")
        @DecimalMin(value = "0.0000001", message = "Quantity must be greater than zero")
        @Digits(integer = 12, fraction = 3, message = "Quantity format is invalid")
        BigDecimal quantity
) {
}
