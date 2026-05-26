package com.sebet.cartservice.cart.delivery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

public record DeliveryScheduleQuoteRequest(
        @NotBlank String addressId,
        @NotBlank String storeId,
        @NotNull Instant scheduledFor,
        @NotNull @Positive BigDecimal orderValue,
        @NotBlank String currency
) {}
