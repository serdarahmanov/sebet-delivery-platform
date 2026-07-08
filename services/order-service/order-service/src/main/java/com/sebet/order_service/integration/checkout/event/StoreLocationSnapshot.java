package com.sebet.order_service.integration.checkout.event;

import java.math.BigDecimal;

public record StoreLocationSnapshot(
        String storeId,
        String storeName,
        BigDecimal lat,
        BigDecimal lng,
        String address
) {
}
