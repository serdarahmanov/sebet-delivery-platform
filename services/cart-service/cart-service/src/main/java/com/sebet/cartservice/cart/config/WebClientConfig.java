package com.sebet.cartservice.cart.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;


@Configuration
public class WebClientConfig {

    private final String promotionBaseUrl;
    private final String productBaseUrl;
    private final String storeBaseUrl;
    private final String deliveryBaseUrl;

    public WebClientConfig(
            @Value("${services.promotion.base-url}") String promotionBaseUrl,
            @Value("${services.product.base-url}") String productBaseUrl,
            @Value("${services.store.base-url}") String storeBaseUrl,
            @Value("${services.delivery.base-url}") String deliveryBaseUrl
    ) {
        this.promotionBaseUrl = promotionBaseUrl;
        this.productBaseUrl = productBaseUrl;
        this.storeBaseUrl = storeBaseUrl;
        this.deliveryBaseUrl = deliveryBaseUrl;
    }

    @Bean
    @Qualifier("promotionWebClient")
    public WebClient promotionWebClient(WebClient.Builder builder) {
        // Netty-level: 2 s backstop. Reactor-level timeout in HttpPromotionClient
        // fires at 500 ms; these handlers should never trigger in normal operation.
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                .responseTimeout(Duration.ofSeconds(2))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(2, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(2, TimeUnit.SECONDS)));

        return builder
                .baseUrl(promotionBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean
    @Qualifier("productWebClient")
    public WebClient productWebClient(WebClient.Builder builder) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                .responseTimeout(Duration.ofMillis(500)).doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(500, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(500, TimeUnit.MILLISECONDS)));

        return builder
                .baseUrl(productBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean
    @Qualifier("storeWebClient")
    public WebClient storeWebClient(WebClient.Builder builder) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                .responseTimeout(Duration.ofMillis(500)).doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(500, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(500, TimeUnit.MILLISECONDS)));

        return builder
                .baseUrl(storeBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean
    @Qualifier("deliveryWebClient")
    public WebClient deliveryWebClient(WebClient.Builder builder) {
        // Netty-level: 3 s backstop. Reactor-level timeout in HttpDeliveryAvailabilityClient
        // fires at 600 ms; these handlers should never trigger in normal operation.
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                .responseTimeout(Duration.ofSeconds(3))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(3, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(3, TimeUnit.SECONDS)));

        return builder
                .baseUrl(deliveryBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
