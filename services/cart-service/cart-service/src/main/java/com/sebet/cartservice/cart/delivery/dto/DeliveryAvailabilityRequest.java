package com.sebet.cartservice.cart.delivery.dto;

import jakarta.validation.constraints.NotBlank;

public record DeliveryAvailabilityRequest(@NotBlank String addressId) {}
