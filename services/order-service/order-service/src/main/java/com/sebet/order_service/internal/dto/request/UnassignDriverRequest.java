package com.sebet.order_service.internal.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /api/v1/internal/orders/{orderId}/unassign-driver.
 *
 * {@code reason} identifies why the driver was removed for audit and notification purposes.
 * Expected values (not enforced at this layer — service layer validates):
 *   DRIVER_ACCIDENT          — driver had an accident mid-delivery
 *   DRIVER_WENT_OFFLINE      — driver became unreachable
 *   DRIVER_VEHICLE_BREAKDOWN — vehicle issue reported by driver or dispatch
 *   ADMIN_OVERRIDE           — manual removal by support staff
 */
public record UnassignDriverRequest(
        @NotBlank String reason
) {}
