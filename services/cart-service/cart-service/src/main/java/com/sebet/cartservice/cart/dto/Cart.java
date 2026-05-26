package com.sebet.cartservice.cart.dto;

import com.sebet.cartservice.cart.model.CartIssue;
import com.sebet.cartservice.cart.model.StoreBasket;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record Cart(
        String cartId,
        List<StoreBasket> storeBaskets,
        BigDecimal totalBasketsCount,
        List<CartIssue> cartIssues,
        Instant createdAt,
        Instant updatedAt
) {
}
