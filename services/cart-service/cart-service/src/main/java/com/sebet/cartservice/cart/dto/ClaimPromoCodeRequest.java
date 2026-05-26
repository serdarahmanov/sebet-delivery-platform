package com.sebet.cartservice.cart.dto;

import jakarta.validation.constraints.NotBlank;

public record ClaimPromoCodeRequest(
        @NotBlank(message = "Promo code is required")
        String code
) {
}
