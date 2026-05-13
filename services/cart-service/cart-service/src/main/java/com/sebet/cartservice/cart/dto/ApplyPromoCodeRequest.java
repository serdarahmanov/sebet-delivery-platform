package com.sebet.cartservice.cart.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ApplyPromoCodeRequest(
       @NotBlank(message = "Promo code is required")
       String promoCode
) {
}
