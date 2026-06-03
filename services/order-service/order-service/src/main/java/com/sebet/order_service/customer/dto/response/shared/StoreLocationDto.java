package com.sebet.order_service.customer.dto.response.shared;

/**
 * Store pin coordinates embedded in multiple customer-facing responses.
 * Mirrors the shape of {@link com.sebet.order_service.cache.dto.StoreLocation}
 * but kept separate so the API contract is decoupled from the cache model.
 */
public record StoreLocationDto(
        double lat,
        double lng
) {}
