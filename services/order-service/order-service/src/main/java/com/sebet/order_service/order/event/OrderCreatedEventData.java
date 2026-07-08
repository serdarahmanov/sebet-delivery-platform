package com.sebet.order_service.order.event;

import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ScheduleType;

import java.math.BigDecimal;

public record OrderCreatedEventData(
        String orderId,
        String customerId,
        String storeId,
        String cartId,
        OrderStatus status,
        ScheduleType scheduleType,
        String scheduledFor,
        BigDecimal subtotalAmount,
        BigDecimal itemDiscountAmount,
        BigDecimal orderDiscountAmount,
        BigDecimal deliveryFeeAmount,
        BigDecimal totalAmount,
        String currency,
        String createdAt
) {
}
