package com.sebet.cartservice.cart.delivery.dto;

import java.util.List;

public record DeliveryAvailabilityResponse(
        String addressId,
        boolean available,
        String zoneId,
        String coverageType,
        List<String> restrictedCategories,
        String reason
) {}
