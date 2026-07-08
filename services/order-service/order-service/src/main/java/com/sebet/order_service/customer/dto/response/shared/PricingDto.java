package com.sebet.order_service.customer.dto.response.shared;

import java.math.BigDecimal;

/**
 * Pricing breakdown embedded in receipt-style detail responses
 * (delivered, scheduled, cancelled orders).
 */
public record PricingDto(
        BigDecimal itemsSubtotal,
        BigDecimal deliveryFee,
        BigDecimal serviceFee,
        BigDecimal smallOrderFee,
        /** Total discount applied from promo codes; zero if none applied. */
        BigDecimal promoDiscount,
        BigDecimal grandTotal
) {}
