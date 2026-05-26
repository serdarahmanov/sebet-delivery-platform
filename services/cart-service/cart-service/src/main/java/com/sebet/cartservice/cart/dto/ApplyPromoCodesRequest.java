package com.sebet.cartservice.cart.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ApplyPromoCodesRequest(
        @NotNull(message = "promoCodes is required")
        @NotEmpty(message = "promoCodes must not be empty")
        List<@Valid ApplyPromoCodeSelectionRequest> promoCodes
) {
}
