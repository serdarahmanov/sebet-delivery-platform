package com.sebet.cartservice.cart.model.redis;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RedisDeliveryQuote {
    private String quoteId;
    private BigDecimal amount;
    private String currency;
    private String display;
    private int etaMin;
    private int etaMax;
    private String etaDisplayLabel;
    private Instant expiresAt;
    private Instant fetchedAt;
    private List<RedisDeliveryOption> availableOptions;
}
