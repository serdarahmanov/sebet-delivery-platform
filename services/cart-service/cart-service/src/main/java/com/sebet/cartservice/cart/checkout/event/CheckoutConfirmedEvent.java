package com.sebet.cartservice.cart.checkout.event;

import com.sebet.cartservice.cart.enums.ScheduleType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CheckoutConfirmedEvent(
        String eventId,
        String eventType,
        String aggregateId,
        String aggregateType,
        Instant occurredAt,
        String source,
        Data data
) {
    public record Data(
            String basketId,
            String cartId,
            String storeId,
            String userId,
            String addressId,
            String feeQuoteId,
            List<Item> items,
            List<String> selectedPromoCodes,
            ScheduleType scheduleType,
            Instant scheduledFor,
            Instant confirmedAt
    ) {}

    public record Item(
            String cartItemId,
            String productId,
            String storeId,
            String sku,
            String name,
            String unit,
            BigDecimal quantity,
            BigDecimal unitPrice,
            String imageUrl
    ) {}
}
