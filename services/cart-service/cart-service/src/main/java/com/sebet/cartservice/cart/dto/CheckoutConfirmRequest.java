package com.sebet.cartservice.cart.dto;

import jakarta.validation.constraints.NotBlank;

public record CheckoutConfirmRequest(
        @NotBlank String feeQuoteId
) {}
