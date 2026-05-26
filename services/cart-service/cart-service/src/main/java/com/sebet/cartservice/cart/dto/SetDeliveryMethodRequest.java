package com.sebet.cartservice.cart.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

public record SetDeliveryMethodRequest(
        @NotBlank String methodId,
        OffsetDateTime scheduledFor
) {}
