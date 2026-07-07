package com.sebet.order_service.customer.dto.response.shared;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * A single line item shared across all order detail responses.
 *
 * {@code productId} is included for the active tracking screen
 * where the client may need to deep-link back to the product.
 * It is omitted (null) for receipt-style responses (delivered,
 * cancelled, scheduled) where deep-linking to the product is
 * not needed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderItemDto(
        /** Null for past-order receipt views; non-null for active order detail. */
        String productId,
        String name,
        String imageUrl,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal subtotal
) {}
