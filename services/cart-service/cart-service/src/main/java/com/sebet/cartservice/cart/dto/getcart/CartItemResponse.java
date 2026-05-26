package com.sebet.cartservice.cart.dto.getcart;

import java.math.BigDecimal;

public record CartItemResponse(
        String cartItemId,
        String productId,
        String storeId,
        String name,
        String brandName,
        String categoryName,
        String imageUrl,
        BigDecimal quantity,
        String unit,
        BigDecimal quantityStep
) {
}
