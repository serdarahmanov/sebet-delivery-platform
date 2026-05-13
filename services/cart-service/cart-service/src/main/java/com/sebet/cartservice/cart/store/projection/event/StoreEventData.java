package com.sebet.cartservice.cart.store.projection.event;

import java.math.BigDecimal;
import java.time.Instant;

public record StoreEventData(
        String storeId,

        String storeName,
        String storeLogoUrl,

        Boolean active,
        Boolean open,
        Boolean acceptingOrders,

        BigDecimal minimumOrderAmount,
        BigDecimal freeDeliveryThreshold,
        BigDecimal baseDeliveryFee,

        Integer estimatedPreparationMinutes,

        String reason,

        Long storeVersion,
        Instant storeUpdatedAt
) {
}
