package com.sebet.cartservice.cart.model.redis;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RedisCartPromoCode {

    private String storeId;

    private String code;

    private Instant appliedAt;

    public RedisCartPromoCode(String storeId, String code) {
        this.storeId = storeId;
        this.code = code;
        this.appliedAt = Instant.now();
    }

}
