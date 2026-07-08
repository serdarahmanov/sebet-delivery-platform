package com.sebet.order_service.integration.checkout.event;

import java.math.BigDecimal;

public record CheckoutItemPayload(
        String cartItemId,
        String productId,
        String storeId,
        String sku,
        String productName,
        String unit,
        BigDecimal quantity,
        Long unitPriceAmount,
        Long grossAmount,
        Long discountAmount,
        Long netAmount,
        String imageUrl
) {
}
