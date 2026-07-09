package com.sebet.order_service.order.event;

import com.sebet.order_service.shared.enums.OrderStatus;

public record DriverAssignmentDeclinedEventData(
        String orderId,
        String customerId,
        String storeId,
        String driverId,
        OrderStatus status,
        String declinedAt,
        String reason
) {
}
