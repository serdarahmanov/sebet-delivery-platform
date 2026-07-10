package com.sebet.order_service.internal.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for internal cancellation endpoints:
 * POST /api/v1/internal/orders/{orderId}/system-cancel
 * POST /api/v1/internal/orders/{orderId}/admin-cancel
 *
 * {@code reason} identifies the cancellation source for audit and notification
 * purposes. Expected values are validated by the service layer:
 *
 *   PAYMENT_FAILED
 *   NO_RIDERS_AVAILABLE
 *   STORE_RESPONSE_TIMEOUT
 *   AWAITING_CUSTOMER_RESPONSE_TIMEOUT
 *   SYSTEM_ERROR
 */
public record SystemCancelOrderRequest(
        @NotBlank String reason
) {}
