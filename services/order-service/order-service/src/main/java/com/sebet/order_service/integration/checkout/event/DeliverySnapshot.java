package com.sebet.order_service.integration.checkout.event;

import java.math.BigDecimal;

public record DeliverySnapshot(
        String addressId,
        String label,
        String street,
        String city,
        BigDecimal lat,
        BigDecimal lng,
        String apartment,
        String entrance,
        String floor,
        String note
) {
}
