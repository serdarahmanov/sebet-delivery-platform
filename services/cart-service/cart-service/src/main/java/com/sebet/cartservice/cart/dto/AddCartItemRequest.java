package com.sebet.cartservice.cart.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AddCartItemRequest(
       @NotBlank(message = "Product id is required")
       String productId,

       @NotBlank(message = "Store id is required")
       String storeId,


       @NotNull(message = "Quantity is required")
       @DecimalMin(value="0.1", message = "Quantity must be at least 0")
       @Digits(integer = 2, fraction = 3, message = "Quantity format is invalid")
       BigDecimal quantity
) {

}
