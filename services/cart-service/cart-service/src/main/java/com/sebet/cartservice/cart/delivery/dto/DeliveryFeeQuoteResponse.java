package com.sebet.cartservice.cart.delivery.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record DeliveryFeeQuoteResponse(
        List<BasketFeeResult> results
) {
    public record BasketFeeResult(
            String storeId,
            String quoteId,
            Fee fee,
            Eta deliveryEta,
            int ttlSeconds,
            Instant expiresAt,
            List<AvailableDeliveryOption> availableOptions
    ) {}

    public record AvailableDeliveryOption(
            String methodId,
            String label,
            Integer etaMinutes,
            BigDecimal fee,
            String quoteId,
            Instant expiresAt
    ) {}

    public record Fee(
            BigDecimal amount,
            String currency,
            String display
    ) {}

    public record Eta(
            EtaMinutes etaMinutes,
            String displayLabel
    ) {}

    public record EtaMinutes(
            int min,
            int max
    ) {}
}
