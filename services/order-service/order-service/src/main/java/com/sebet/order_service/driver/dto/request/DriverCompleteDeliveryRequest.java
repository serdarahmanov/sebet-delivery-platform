package com.sebet.order_service.driver.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Verification code submitted by the driver to complete delivery.
 *
 * Endpoint : POST /api/v1/driver/orders/{orderId}/complete
 *
 * The driver reads the code displayed on the customer's screen and submits it
 * here.  The service validates it against Cache 7 (order:verification:{orderId}).
 * A mismatch returns 400; a missing code (TTL expired) returns 404.
 */
public record DriverCompleteDeliveryRequest(
        @NotBlank String verificationCode
) {}
