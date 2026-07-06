package com.sebet.order_service.driver.dto.response;

/**
 * Confirmation returned after the driver marks arrival at the customer's address.
 *
 * Endpoint  : POST /api/v1/driver/orders/{orderId}/arrive
 * Transition: OUT_FOR_DELIVERY → ARRIVED
 *
 * The verification code is NOT included here — it is pushed to the customer
 * via WebSocket and stored in Cache 7.  The customer shows the code to the
 * driver; the driver submits it via POST /complete.
 */
public record DriverArriveResponse(
        String orderId,
        /** Always {@code "ARRIVED"} on success. */
        String status
) {}
