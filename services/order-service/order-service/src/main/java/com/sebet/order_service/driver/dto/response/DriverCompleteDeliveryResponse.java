package com.sebet.order_service.driver.dto.response;

/**
 * Confirmation returned after the driver successfully completes the delivery.
 *
 * Endpoint  : POST /api/v1/driver/orders/{orderId}/complete
 * Transition: ARRIVED → DELIVERED
 */
public record DriverCompleteDeliveryResponse(
        String orderId,
        /** Always {@code "DELIVERED"} on success. */
        String status,
        /** ISO-8601 timestamp of when the delivery was confirmed. */
        String deliveredAt
) {}
