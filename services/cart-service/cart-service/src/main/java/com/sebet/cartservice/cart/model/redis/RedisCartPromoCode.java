package com.sebet.cartservice.cart.model.redis;

import com.sebet.cartservice.cart.enums.PromoCodeState;
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

    private String code;
    private PromoCodeState state;
    private Instant claimedAt;
    private Instant selectedAt;

    public RedisCartPromoCode(String code) {
        this.code = code == null ? null : code.trim().toUpperCase();
        this.state = PromoCodeState.SAVED;
        this.claimedAt = Instant.now();
    }
}
