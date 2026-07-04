package com.sebet.order_service.integration.checkout.event;

import java.math.BigDecimal;
import java.util.Objects;

public record CheckoutDeliveryAddress(
        String street,
        String city,
        BigDecimal lat,
        BigDecimal lng,
        String apartment,
        String entrance,
        String floor,
        String note
) {

    public CheckoutDeliveryAddress {
        Objects.requireNonNull(street, "street must not be null");
        Objects.requireNonNull(city, "city must not be null");
        Objects.requireNonNull(lat, "lat must not be null");
        Objects.requireNonNull(lng, "lng must not be null");
    }
}
