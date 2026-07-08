package com.sebet.order_service.order.event;

import com.sebet.order_service.shared.enums.OrderCancellationReason;
import com.sebet.order_service.shared.enums.OrderCancelledBy;
import com.sebet.order_service.shared.enums.OrderStatus;

public record OrderStatusTransitionEventData(
        String orderId,
        String customerId,
        String storeId,
        String driverId,
        OrderStatus previousStatus,
        OrderStatus newStatus,
        String changedByType,
        String changedById,
        String reason,
        String metadataJson,
        OrderCancelledBy cancelledBy,
        OrderCancellationReason cancellationReason,
        String changedAt,
        String deliveredAt,
        String cancelledAt
) {
}
