package com.sebet.order_service.driver.dto.response;

/**
 * Confirmation returned after the driver successfully declines the assignment.
 *
 * Endpoint  : POST /api/v1/driver/orders/{orderId}/decline
 * Effect    : clears driverId and driverAssignedAt on the order; order status is unchanged.
 *
 * The order remains in its current status (PENDING, CONFIRMED, or READY_FOR_PICKUP)
 * so dispatch can reassign another driver.
 */
public record DriverDeclineResponse(
        String orderId,
        /** Current order status — unchanged by the decline. */
        String status
) {}
