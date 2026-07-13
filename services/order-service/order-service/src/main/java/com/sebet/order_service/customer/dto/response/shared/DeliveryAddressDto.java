package com.sebet.order_service.customer.dto.response.shared;

/**
 * Delivery address embedded in multiple customer-facing responses.
 * Mirrors the shape of {@link com.sebet.order_service.cache.dto.DeliveryAddress}
 * but kept separate so the API contract is decoupled from the cache model.
 */
public record DeliveryAddressDto(
        String street,
        String city,
        double lat,
        double lng,
        String phoneNumber
) {}
