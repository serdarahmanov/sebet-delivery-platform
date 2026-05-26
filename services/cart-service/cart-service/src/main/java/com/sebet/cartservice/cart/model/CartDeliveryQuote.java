package com.sebet.cartservice.cart.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CartDeliveryQuote(
        String quoteId,
        Instant expiresAt,
        Fee fee,
        Eta eta
) {
    public record Fee(BigDecimal amount, String currency, String display) {}

    public record Eta(int min, int max, String displayLabel) {}
}
