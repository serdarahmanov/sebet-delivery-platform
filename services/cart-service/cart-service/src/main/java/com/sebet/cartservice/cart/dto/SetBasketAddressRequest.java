package com.sebet.cartservice.cart.dto;

import jakarta.validation.constraints.NotBlank;

public record SetBasketAddressRequest(@NotBlank String addressId) {}
