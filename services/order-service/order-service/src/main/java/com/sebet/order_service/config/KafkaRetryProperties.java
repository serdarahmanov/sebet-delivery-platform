package com.sebet.order_service.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "order-service.kafka.checkout-events")
public class KafkaRetryProperties {

    private String topic = "checkout-events";
    private String dltTopic = "checkout-events.DLT";
    private boolean validateTopics = false;
    private Retry retry = new Retry();
    private InProgressRetry inProgressRetry = new InProgressRetry();
    private ProcessedEvents processedEvents = new ProcessedEvents();

    @Getter
    @Setter
    public static class Retry {
        private long initialIntervalMs = 1000;
        private double multiplier = 2.0;
        private long maxIntervalMs = 10000;
        private int maxAttempts = 3;
    }

    @Getter
    @Setter
    public static class InProgressRetry {
        private long intervalMs = 5000;
        private long maxAttempts = 12;
    }

    @Getter
    @Setter
    public static class ProcessedEvents {
        private Duration inProgressLease = Duration.ofSeconds(30);
        private Duration completedRetention = Duration.ofDays(7);
        private Duration abandonedInProgressRetention = Duration.ofHours(1);
        private long cleanupIntervalMs = 600000;
    }
}
