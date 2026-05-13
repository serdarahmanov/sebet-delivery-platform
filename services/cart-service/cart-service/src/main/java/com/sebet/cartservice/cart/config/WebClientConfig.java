package com.sebet.cartservice.cart.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {


    @Bean
    @Qualifier("promotionWebClient")
    public WebClient promotionWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl("http://promotion-service:8080")
                .build();
    }

    @Bean
    @Qualifier("productWebClient")
    public WebClient productWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl("http://product-service:8080")
                .build();
    }

    @Bean
    @Qualifier("storeWebClient")
    public WebClient storeWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl("http://store-service:8080")
                .build();
    }

    @Bean
    @Qualifier("deliveryWebClient")
    public WebClient deliveryWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl("http://delivery-service:8080")
                .build();
    }
}
