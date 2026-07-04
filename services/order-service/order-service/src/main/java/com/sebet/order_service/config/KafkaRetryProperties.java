package com.sebet.order_service.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "order-service.kafka.checkout-events")
public class KafkaRetryProperties {

    private String topic = "checkout-events";
    private String dltTopic = "checkout-events.DLT";
    private boolean validateTopics = false;
    private Retry retry = new Retry();

    @Getter
    @Setter
    public static class Retry {
        private long initialIntervalMs = 1000;
        private double multiplier = 2.0;
        private long maxIntervalMs = 10000;
        private int maxAttempts = 3;
    }
}
