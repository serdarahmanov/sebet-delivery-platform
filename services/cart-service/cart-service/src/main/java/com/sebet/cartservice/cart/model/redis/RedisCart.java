package com.sebet.cartservice.cart.model.redis;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RedisCart {

    private String userId;
    private String cartId;
    private List<RedisCartItem> items = new ArrayList<>();

    private List<RedisCartPromoCode> promoCodes = new ArrayList<>();

    private Instant createdAt;

    private Instant updatedAt;

    public RedisCart(String userId) {
        this.cartId= UUID.randomUUID().toString(); ;
        this.userId = userId;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }


}
