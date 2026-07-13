package com.sebet.order_service.order.command;

import com.sebet.order_service.shared.enums.ScheduleType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

public record CreateOrderCommand(
        String cartId,
        String customerId,
        String storeId,
        ScheduleType scheduleType,
        OffsetDateTime scheduledFor,
        BigDecimal subtotalAmount,
        BigDecimal itemDiscountAmount,
        BigDecimal orderDiscountAmount,
        BigDecimal deliveryFeeAmount,
        BigDecimal serviceFeeAmount,
        BigDecimal smallOrderFeeAmount,
        BigDecimal totalAmount,
        String currency,
        String deliveryAddressJson,
        String phoneNumber,
        BigDecimal deliveryLat,
        BigDecimal deliveryLng,
        BigDecimal storeLat,
        BigDecimal storeLng,
        String feeQuoteId,
        List<String> selectedPromoCodes,
        List<CreateOrderItemCommand> items
) {

    public CreateOrderCommand {
        Objects.requireNonNull(cartId, "cartId must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(scheduleType, "scheduleType must not be null");
        Objects.requireNonNull(subtotalAmount, "subtotalAmount must not be null");
        Objects.requireNonNull(itemDiscountAmount, "itemDiscountAmount must not be null");
        Objects.requireNonNull(orderDiscountAmount, "orderDiscountAmount must not be null");
        Objects.requireNonNull(deliveryFeeAmount, "deliveryFeeAmount must not be null");
        Objects.requireNonNull(serviceFeeAmount, "serviceFeeAmount must not be null");
        Objects.requireNonNull(smallOrderFeeAmount, "smallOrderFeeAmount must not be null");
        Objects.requireNonNull(totalAmount, "totalAmount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(deliveryAddressJson, "deliveryAddressJson must not be null");
        Objects.requireNonNull(storeLat, "storeLat must not be null");
        Objects.requireNonNull(storeLng, "storeLng must not be null");
        if (subtotalAmount.signum() < 0) throw new IllegalArgumentException("subtotalAmount must be >= 0");
        if (itemDiscountAmount.signum() < 0) throw new IllegalArgumentException("itemDiscountAmount must be >= 0");
        if (orderDiscountAmount.signum() < 0) throw new IllegalArgumentException("orderDiscountAmount must be >= 0");
        if (deliveryFeeAmount.signum() < 0) throw new IllegalArgumentException("deliveryFeeAmount must be >= 0");
        if (serviceFeeAmount.signum() < 0) throw new IllegalArgumentException("serviceFeeAmount must be >= 0");
        if (smallOrderFeeAmount.signum() < 0) throw new IllegalArgumentException("smallOrderFeeAmount must be >= 0");
        if (totalAmount.signum() < 0) throw new IllegalArgumentException("totalAmount must be >= 0");
        if (scheduleType == ScheduleType.SCHEDULED) {
            Objects.requireNonNull(scheduledFor, "scheduledFor must not be null when scheduleType is SCHEDULED");
        }
        if (scheduleType == ScheduleType.ASAP && scheduledFor != null) {
            throw new IllegalArgumentException("scheduledFor must be null when scheduleType is ASAP");
        }
        selectedPromoCodes = selectedPromoCodes == null ? List.of() : List.copyOf(selectedPromoCodes);
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
    }
}
