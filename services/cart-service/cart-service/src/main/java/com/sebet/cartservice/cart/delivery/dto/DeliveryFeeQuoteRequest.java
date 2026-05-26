package com.sebet.cartservice.cart.delivery.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

public record DeliveryFeeQuoteRequest(
        @NotBlank String customerId,
        @NotBlank String currency,
        @NotEmpty @Valid List<BasketItem> baskets
) {
    public record BasketItem(
            @NotBlank String storeId,
            @NotBlank String addressId,
            @NotNull @Positive BigDecimal orderValue
    ) {}
}
