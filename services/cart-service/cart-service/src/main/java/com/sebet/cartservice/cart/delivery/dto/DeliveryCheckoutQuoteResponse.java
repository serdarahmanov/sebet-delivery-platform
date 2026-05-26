package com.sebet.cartservice.cart.delivery.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record DeliveryCheckoutQuoteResponse(
        List<BasketCheckoutResult> results
) {
    public record BasketCheckoutResult(
            String storeId,
            boolean available,
            String unavailableReason,
            List<String> restrictedCategories,
            int ttlSeconds,
            List<CheckoutDeliveryOption> availableOptions
    ) {}

    public record CheckoutDeliveryOption(
            String methodId,
            String label,
            Fee fee,
            EtaMinutes etaMinutes,    // null for scheduled
            String etaDisplayLabel,   // null for scheduled
            String quoteId,
            Instant expiresAt
    ) {}

    public record Fee(BigDecimal amount, String currency, String display) {}

    public record EtaMinutes(int min, int max) {}
}
