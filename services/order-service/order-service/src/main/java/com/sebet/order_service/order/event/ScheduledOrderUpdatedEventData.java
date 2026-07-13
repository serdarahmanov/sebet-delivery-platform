package com.sebet.order_service.order.event;

public record ScheduledOrderUpdatedEventData(
        String orderId,
        String customerId,
        String storeId,
        /** New scheduledFor ISO-8601 timestamp, null if not changed. */
        String scheduledWindowStart,
        /** True if delivery address was updated. */
        boolean addressUpdated,
        /** True if phone number was updated. */
        boolean phoneNumberUpdated,
        String updatedAt
) {
}
