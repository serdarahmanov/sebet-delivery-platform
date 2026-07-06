package com.sebet.order_service.internal.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /api/v1/internal/orders/{orderId}/assign-driver.
 *
 * Sent by the dispatch service when a driver is matched to an order.
 * Can also be used to overwrite an existing assignment (reassignment).
 */
public record AssignDriverRequest(
        @NotBlank String driverId
) {}
