package com.sebet.order_service.driver.dto.response;

/**
 * Confirmation returned after the driver successfully confirms pickup.
 *
 * Endpoint : POST /api/v1/driver/orders/{orderId}/pickup
 * Transition: READY_FOR_PICKUP → OUT_FOR_DELIVERY
 */
public record DriverPickupResponse(
        String orderId,
        /** Always {@code "OUT_FOR_DELIVERY"} on success. */
        String status
) {}
