package com.sebet.order_service.internal.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /api/v1/internal/orders/{orderId}/system-cancel.
 *
 * {@code reason} identifies the cancellation source for audit and notification purposes.
 * Expected values (not enforced at this layer — service layer validates):
 *   STORE_TIMEOUT                    — store did not accept within the allowed window
 *   PAYMENT_FAILED                   — payment service could not capture payment
 *   FRAUD_DETECTED                   — fraud detection service flagged the order
 *   ADMIN_OVERRIDE                   — manual cancellation by support staff
 */
public record SystemCancelOrderRequest(
        @NotBlank String reason
) {}
