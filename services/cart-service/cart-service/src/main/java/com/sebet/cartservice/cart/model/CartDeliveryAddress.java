package com.sebet.cartservice.cart.model;

import java.math.BigDecimal;

public record CartDeliveryAddress(
        String addressId,
        String label,
        String formattedAddress,

        BigDecimal latitude,
        BigDecimal longitude,

        Boolean isInDeliveryZone
) {
}
