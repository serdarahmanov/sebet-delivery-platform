package com.sebet.cartservice.cart.checkout.event;

import com.sebet.cartservice.cart.enums.ScheduleType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CheckoutConfirmedEvent(
        String eventId,
        String eventType,
        Integer eventVersion,
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
            String customerId,
            String addressId,
            String feeQuoteId,
            Money money,
            DeliveryAddress deliveryAddress,
            StoreLocation storeLocation,
            List<Item> items,
            List<String> selectedPromoCodes,
            ScheduleType scheduleType,
            Instant scheduledFor,
            Instant confirmedAt
    ) {}

    public record Money(
            Long subtotalAmount,
            Long itemDiscountAmount,
            Long orderDiscountAmount,
            Long deliveryFeeAmount,
            Long serviceFeeAmount,
            Long smallOrderFeeAmount,
            Long totalAmount,
            String currency
    ) {}

    public record DeliveryAddress(
            String addressId,
            String label,
            String street,
            String city,
            BigDecimal lat,
            BigDecimal lng,
            String apartment,
            String entrance,
            String floor,
            String note
    ) {}

    public record StoreLocation(
            String storeId,
            String storeName,
            BigDecimal lat,
            BigDecimal lng,
            String address
    ) {}

    public record Item(
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
    ) {}
}
