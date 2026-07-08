package com.sebet.order_service.integration.checkout.event;

public record MoneyBreakdown(
        Long subtotalAmount,
        Long itemDiscountAmount,
        Long orderDiscountAmount,
        Long deliveryFeeAmount,
        Long serviceFeeAmount,
        Long smallOrderFeeAmount,
        Long totalAmount,
        String currency
) {
}
