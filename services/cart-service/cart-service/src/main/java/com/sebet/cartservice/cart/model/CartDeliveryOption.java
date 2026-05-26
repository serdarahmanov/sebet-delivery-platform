package com.sebet.cartservice.cart.model;

import java.math.BigDecimal;

public record CartDeliveryOption(
        String methodId,
        String label,
        Integer etaMin,
        Integer etaMax,
        String etaDisplayLabel,
        BigDecimal fee,
        String currency,
        String feeDisplay
) {}
