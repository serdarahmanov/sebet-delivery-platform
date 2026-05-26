package com.sebet.cartservice.cart.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BatchUpsertCartItemsRequest(
        @NotNull(message = "Items list is required")
        @Size(min = 1, max = 100)
        List<@Valid BatchCartItemRequest> items
) {
}
