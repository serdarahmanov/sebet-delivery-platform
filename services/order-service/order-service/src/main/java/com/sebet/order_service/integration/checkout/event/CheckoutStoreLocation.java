package com.sebet.order_service.integration.checkout.event;

import java.math.BigDecimal;
import java.util.Objects;

public record CheckoutStoreLocation(
        BigDecimal lat,
        BigDecimal lng
) {

    public CheckoutStoreLocation {
        Objects.requireNonNull(lat, "lat must not be null");
        Objects.requireNonNull(lng, "lng must not be null");
    }
}
