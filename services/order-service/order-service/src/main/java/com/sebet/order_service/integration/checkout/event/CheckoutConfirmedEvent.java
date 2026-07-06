package com.sebet.order_service.integration.checkout.event;

import com.sebet.order_service.shared.enums.ScheduleType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

public record CheckoutConfirmedEvent(
        String cartId,
        String customerId,
        String storeId,
        ScheduleType scheduleType,
        OffsetDateTime scheduledFor,
        BigDecimal subtotalAmount,
        BigDecimal itemDiscountAmount,
        BigDecimal orderDiscountAmount,
        BigDecimal deliveryFeeAmount,
        BigDecimal totalAmount,
        String currency,
        CheckoutDeliveryAddress deliveryAddress,
        CheckoutStoreLocation storeLocation,
        List<CheckoutConfirmedItem> items
) {

    public CheckoutConfirmedEvent {
        Objects.requireNonNull(cartId, "cartId must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(scheduleType, "scheduleType must not be null");
        Objects.requireNonNull(subtotalAmount, "subtotalAmount must not be null");
        Objects.requireNonNull(itemDiscountAmount, "itemDiscountAmount must not be null");
        Objects.requireNonNull(orderDiscountAmount, "orderDiscountAmount must not be null");
        Objects.requireNonNull(deliveryFeeAmount, "deliveryFeeAmount must not be null");
        Objects.requireNonNull(totalAmount, "totalAmount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(deliveryAddress, "deliveryAddress must not be null");
        if (subtotalAmount.signum() < 0) throw new IllegalArgumentException("subtotalAmount must be >= 0");
        if (itemDiscountAmount.signum() < 0) throw new IllegalArgumentException("itemDiscountAmount must be >= 0");
        if (orderDiscountAmount.signum() < 0) throw new IllegalArgumentException("orderDiscountAmount must be >= 0");
        if (deliveryFeeAmount.signum() < 0) throw new IllegalArgumentException("deliveryFeeAmount must be >= 0");
        if (totalAmount.signum() < 0) throw new IllegalArgumentException("totalAmount must be >= 0");
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
    }
}
