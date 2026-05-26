package com.sebet.cartservice.cart.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.fees")
public class CartFeeProperties {
    private BigDecimal serviceFee = BigDecimal.ZERO;
}
