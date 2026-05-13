package com.sebet.cartservice.cart.model;

import com.sebet.cartservice.cart.enums.FreeDeliveryReason;

import java.math.BigDecimal;

public record FreeDeliveryInfo(
        Boolean eligible,
        BigDecimal thresholdAmount,
        BigDecimal currentEligibleAmount,
        BigDecimal amountRemaining,
        Boolean applied,
        FreeDeliveryReason reason) {
}
