package com.sebet.order_service.store.dto.response.shared;

/**
 * Masked customer identity shown to store staff.
 *
 * The full customer name and contact details are intentionally withheld.
 * Only the display name is exposed so staff can reference the order
 * in conversation (e.g. "the order for Serdar A.") without exposing PII.
 *
 * If direct contact is ever needed (e.g. wrong address), that should go
 * through the platform's support channel, not a raw phone number.
 */
public record CustomerInfoDto(

        /**
         * First name + last initial, e.g. {@code "Serdar A."}.
         * Computed by the service layer at response-build time.
         */
        String displayName

) {}
