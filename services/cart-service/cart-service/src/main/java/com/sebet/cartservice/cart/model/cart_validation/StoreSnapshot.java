package com.sebet.cartservice.cart.model.cart_validation;

import java.math.BigDecimal;

public record StoreSnapshot(

        String storeId,

        String name,
        String logoUrl,

        boolean exists,
        boolean open,

        BigDecimal minimumOrderAmount,

        BigDecimal deliveryFee,
        BigDecimal freeDeliveryThreshold
) {
}
