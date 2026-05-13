package com.sebet.cartservice.cart.inventory.projection.event;

import com.sebet.cartservice.cart.enums.StockStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record InventoryEventData(
        String productId,
        String storeId,

        BigDecimal availableQuantity,
        StockStatus stockStatus,
        Boolean available,

        BigDecimal reservedQuantity,
        BigDecimal releasedQuantity,

        String orderId,
        String reason,

        Long inventoryVersion,
        Instant inventoryUpdatedAt
) {
}
