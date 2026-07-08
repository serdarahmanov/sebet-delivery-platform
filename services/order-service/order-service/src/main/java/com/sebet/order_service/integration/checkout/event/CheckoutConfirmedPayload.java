package com.sebet.order_service.integration.checkout.event;

import java.time.Instant;
import java.util.List;

public record CheckoutConfirmedPayload(
        String basketId,
        String cartId,
        String storeId,
        String customerId,
        String addressId,
        String feeQuoteId,
        MoneyBreakdown money,
        DeliverySnapshot deliveryAddress,
        StoreLocationSnapshot storeLocation,
        List<CheckoutItemPayload> items,
        List<String> selectedPromoCodes,
        CheckoutScheduleType scheduleType,
        Instant scheduledFor,
        Instant confirmedAt
) {
}
