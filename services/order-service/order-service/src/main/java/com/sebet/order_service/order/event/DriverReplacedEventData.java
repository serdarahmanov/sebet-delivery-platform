package com.sebet.order_service.order.event;

import com.sebet.order_service.shared.enums.OrderStatus;

public record DriverReplacedEventData(
        String orderId,
        String customerId,
        String storeId,
        String previousDriverId,
        String newDriverId,
        OrderStatus status,
        String replacedAt
) {
}
