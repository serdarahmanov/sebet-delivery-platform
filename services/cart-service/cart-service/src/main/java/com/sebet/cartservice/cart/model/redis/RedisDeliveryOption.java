package com.sebet.cartservice.cart.model.redis;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RedisDeliveryOption {
    private String methodId;
    private String label;
    private BigDecimal fee;          // fee amount
    private String currency;
    private String feeDisplay;
    private Integer etaMin;
    private Integer etaMax;
    private String etaDisplayLabel;
    private String quoteId;
    private Instant expiresAt;
}
