package com.sebet.cartservice.cart.model.redis;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RedisCartItem {

    private String cartItemId;

    private String productId;

    private String storeId;

    private BigDecimal quantity;

    private Instant addedAt;

    private BigDecimal unitPriceSnapshot;

    private Instant updatedAt;

    public RedisCartItem(String productId, String storeId, BigDecimal quantity,BigDecimal unitPriceSnapshot) {
        this.cartItemId = UUID.randomUUID().toString();
        this.productId = productId;
        this.storeId = storeId;
        this.quantity = quantity;
        this.addedAt = Instant.now();
        this.updatedAt= Instant.now();
        this.unitPriceSnapshot = unitPriceSnapshot;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

}
