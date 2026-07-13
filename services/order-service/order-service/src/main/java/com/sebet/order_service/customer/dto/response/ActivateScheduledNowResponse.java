package com.sebet.order_service.customer.dto.response;

public record ActivateScheduledNowResponse(
        String orderId,
        String newStatus,
        String changedAt
) {}
