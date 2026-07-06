package com.sebet.order_service.internal.dto.response;

/**
 * Confirmation returned after a driver is successfully assigned to an order.
 *
 * Endpoint  : POST /api/v1/internal/orders/{orderId}/assign-driver
 */
public record AssignDriverResponse(
        String orderId,
        String driverId,
        /** ISO-8601 timestamp of when the driver was assigned. */
        String driverAssignedAt
) {}
