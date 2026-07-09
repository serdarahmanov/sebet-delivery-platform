package com.sebet.order_service.order.event;

import com.sebet.order_service.shared.enums.OrderStatus;

public record DriverUnassignedEventData(
        String orderId,
        String customerId,
        String storeId,
        String previousDriverId,
        OrderStatus status,
        String unassignedAt,
        String reason
) {
}
