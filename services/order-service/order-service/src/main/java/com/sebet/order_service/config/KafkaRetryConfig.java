package com.sebet.order_service.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.ValidationException;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import com.sebet.order_service.integration.checkout.consumer.ProcessedEventInProgressException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties({KafkaRetryProperties.class, OrderEventsKafkaProperties.class})
public class KafkaRetryConfig {

    private final KafkaRetryProperties properties;
    private final OrderEventsKafkaProperties orderEventsProperties;

    @PostConstruct
    void validateRetryConfiguration() {
        if (properties.getInProgressRetry().getIntervalMs() <= 0) {
            throw new IllegalStateException("Checkout in-progress retry interval must be positive");
        }
        if (properties.getInProgressRetry().getMaxAttempts() <= 0) {
            throw new IllegalStateException("Checkout in-progress retry max attempts must be positive");
        }
        if (properties.getProcessedEvents().getInProgressLease().isZero()
                || properties.getProcessedEvents().getInProgressLease().isNegative()) {
            throw new IllegalStateException("Checkout processed-event in-progress lease must be positive");
        }
        long inProgressRetryWindowMs;
        try {
            inProgressRetryWindowMs = Math.multiplyExact(
                    properties.getInProgressRetry().getIntervalMs(),
                    properties.getInProgressRetry().getMaxAttempts()
            );
        } catch (ArithmeticException exception) {
            inProgressRetryWindowMs = Long.MAX_VALUE;
        }
        long leaseMs = properties.getProcessedEvents().getInProgressLease().toMillis();
        if (inProgressRetryWindowMs < leaseMs) {
            throw new IllegalStateException(
                    "Checkout in-progress retry window must be greater than or equal to processed-event lease"
            );
        }
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            KafkaOperations<Object, Object> kafkaOperations
    ) {
        return new DeadLetterPublishingRecoverer(
                kafkaOperations,
                (record, exception) -> new TopicPartition(dltTopic(record.topic()), record.partition())
        );
    }

    private String dltTopic(String sourceTopic) {
        if (orderEventsProperties.getTopic().equals(sourceTopic)) {
            return orderEventsProperties.getDltTopic();
        }
        return properties.getDltTopic();
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            DeadLetterPublishingRecoverer deadLetterPublishingRecoverer
    ) {
        ExponentialBackOff backOff = new ExponentialBackOff(
                properties.getRetry().getInitialIntervalMs(),
                properties.getRetry().getMultiplier()
        );
        backOff.setMaxInterval(properties.getRetry().getMaxIntervalMs());
        backOff.setMaxAttempts(properties.getRetry().getMaxAttempts());

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(deadLetterPublishingRecoverer, backOff);
        errorHandler.setBackOffFunction((record, exception) -> {
            if (exception instanceof ProcessedEventInProgressException
                    || exception.getCause() instanceof ProcessedEventInProgressException) {
                return new FixedBackOff(
                        properties.getInProgressRetry().getIntervalMs(),
                        properties.getInProgressRetry().getMaxAttempts()
                );
            }
            return backOff;
        });
        errorHandler.setSeekAfterError(true);
        // TODO: add operational alerts for DLT publish failures and consumer lag.
        errorHandler.addNotRetryableExceptions(
                DeserializationException.class,
                SerializationException.class,
                MessageConversionException.class,
                ValidationException.class,
                IllegalArgumentException.class,
                DataIntegrityViolationException.class
        );
        return errorHandler;
    }
}
