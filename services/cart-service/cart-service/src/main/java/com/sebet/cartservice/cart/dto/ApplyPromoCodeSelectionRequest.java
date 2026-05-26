package com.sebet.cartservice.cart.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ApplyPromoCodeSelectionRequest(
        @NotBlank(message = "Promo code is required")
        String code,
        @NotNull(message = "selected flag is required")
        Boolean selected
) {
}
