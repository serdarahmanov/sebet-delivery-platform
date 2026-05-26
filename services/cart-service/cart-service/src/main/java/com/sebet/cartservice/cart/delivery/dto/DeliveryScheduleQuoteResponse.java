package com.sebet.cartservice.cart.delivery.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record DeliveryScheduleQuoteResponse(
        String quoteId,
        Fee fee,
        int ttlSeconds,
        Instant expiresAt,
        String rejectionReason
) {
    public record Fee(BigDecimal amount, String currency, String display) {}

    public boolean isAccepted() {
        return rejectionReason == null && quoteId != null && fee != null && fee.amount() != null;
    }
}
