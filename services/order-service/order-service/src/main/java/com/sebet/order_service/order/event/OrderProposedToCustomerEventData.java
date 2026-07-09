package com.sebet.order_service.order.event;

public record OrderProposedToCustomerEventData(
        String orderId,
        String customerId,
        String storeId,
        String itemsJson,
        String proposedAt
) {}
