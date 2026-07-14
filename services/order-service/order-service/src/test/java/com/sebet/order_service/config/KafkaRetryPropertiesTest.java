package com.sebet.order_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaRetryPropertiesTest {

    @Test
    void bindsCheckoutKafkaRetryAndDltProperties() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("order-service.kafka.checkout-events.topic", "checkout-events-custom")
                .withProperty("order-service.kafka.checkout-events.dlt-topic", "checkout-events-custom.DLT")
                .withProperty("order-service.kafka.checkout-events.validate-topics", "true")
                .withProperty("order-service.kafka.checkout-events.retry.initial-interval-ms", "250")
                .withProperty("order-service.kafka.checkout-events.retry.multiplier", "1.5")
                .withProperty("order-service.kafka.checkout-events.retry.max-interval-ms", "5000")
                .withProperty("order-service.kafka.checkout-events.retry.max-attempts", "7")
                .withProperty("order-service.kafka.checkout-events.in-progress-retry.interval-ms", "3000")
                .withProperty("order-service.kafka.checkout-events.in-progress-retry.max-attempts", "20")
                .withProperty("order-service.kafka.checkout-events.processed-events.in-progress-lease", "PT45S")
                .withProperty("order-service.kafka.checkout-events.processed-events.completed-retention", "PT240H")
                .withProperty("order-service.kafka.checkout-events.processed-events.abandoned-in-progress-retention", "PT2H")
                .withProperty("order-service.kafka.checkout-events.processed-events.cleanup-interval-ms", "300000");

        KafkaRetryProperties properties = Binder.get(environment)
                .bind("order-service.kafka.checkout-events", KafkaRetryProperties.class)
                .orElseThrow(() -> new AssertionError("Kafka retry properties did not bind"));

        assertThat(properties.getTopic()).isEqualTo("checkout-events-custom");
        assertThat(properties.getDltTopic()).isEqualTo("checkout-events-custom.DLT");
        assertThat(properties.isValidateTopics()).isTrue();
        assertThat(properties.getRetry().getInitialIntervalMs()).isEqualTo(250);
        assertThat(properties.getRetry().getMultiplier()).isEqualTo(1.5);
        assertThat(properties.getRetry().getMaxIntervalMs()).isEqualTo(5000);
        assertThat(properties.getRetry().getMaxAttempts()).isEqualTo(7);
        assertThat(properties.getInProgressRetry().getIntervalMs()).isEqualTo(3000);
        assertThat(properties.getInProgressRetry().getMaxAttempts()).isEqualTo(20);
        assertThat(properties.getProcessedEvents().getInProgressLease()).isEqualTo(Duration.ofSeconds(45));
        assertThat(properties.getProcessedEvents().getCompletedRetention()).isEqualTo(Duration.ofHours(240));
        assertThat(properties.getProcessedEvents().getAbandonedInProgressRetention()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.getProcessedEvents().getCleanupIntervalMs()).isEqualTo(300000);
    }

    @Test
    void bindsOrderEventsKafkaProperties() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("order-service.kafka.order-events.topic", "order-events-custom")
                .withProperty("order-service.kafka.order-events.group-id", "projection-group")
                .withProperty("order-service.kafka.order-events.auto-startup", "true")
                .withProperty("order-service.kafka.order-events.dlt-topic", "order-events-custom.DLT")
                .withProperty("order-service.kafka.order-events.validate-topics", "true");

        OrderEventsKafkaProperties properties = Binder.get(environment)
                .bind("order-service.kafka.order-events", OrderEventsKafkaProperties.class)
                .orElseThrow(() -> new AssertionError("Order event Kafka properties did not bind"));

        assertThat(properties.getTopic()).isEqualTo("order-events-custom");
        assertThat(properties.getGroupId()).isEqualTo("projection-group");
        assertThat(properties.isAutoStartup()).isTrue();
        assertThat(properties.getDltTopic()).isEqualTo("order-events-custom.DLT");
        assertThat(properties.isValidateTopics()).isTrue();
    }

    @Test
    void defaultInProgressRetryWindowCoversProcessedEventLease() {
        KafkaRetryProperties properties = new KafkaRetryProperties();
        KafkaRetryConfig config = new KafkaRetryConfig(properties, new OrderEventsKafkaProperties());

        config.validateRetryConfiguration();
    }

    @Test
    void rejectsInProgressRetryWindowShorterThanProcessedEventLease() {
        KafkaRetryProperties properties = new KafkaRetryProperties();
        properties.getInProgressRetry().setIntervalMs(1000);
        properties.getInProgressRetry().setMaxAttempts(5);
        properties.getProcessedEvents().setInProgressLease(Duration.ofSeconds(30));
        KafkaRetryConfig config = new KafkaRetryConfig(properties, new OrderEventsKafkaProperties());

        assertThatThrownBy(config::validateRetryConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retry window");
    }
}
