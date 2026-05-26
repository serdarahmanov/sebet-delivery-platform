package com.sebet.cartservice.cart.config.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@Slf4j
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            KafkaTemplate<Object, Object> kafkaTemplate
    ) {
        return new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> {
                    String dltTopic = record.topic() + ".DLT";

                    log.error(
                            "Sending Kafka message to DLT. originalTopic={}, dltTopic={}, partition={}, offset={}, key={}, value={}",
                            record.topic(),
                            dltTopic,
                            record.partition(),
                            record.offset(),
                            record.key(),
                            record.value(),
                            exception
                    );

                    /*
                     * Send failed message to same partition number in DLT.
                     * So product-events partition 0 goes to product-events.DLT partition 0.
                     */
                    return new TopicPartition(dltTopic, record.partition());
                }
        );
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            DeadLetterPublishingRecoverer deadLetterPublishingRecoverer
    ) {
        /*
         * Retry 3 times with 1 second delay.
         *
         * Total processing attempts:
         * 1 original attempt + 3 retries
         */
        FixedBackOff backOff = new FixedBackOff(1000L, 3L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                deadLetterPublishingRecoverer,
                backOff
        );

        /*
         * After message is successfully sent to DLT,
         * the consumer can move forward instead of blocking forever.
         */
        errorHandler.setCommitRecovered(true);

        /*
         * Malformed / unparseable messages will never succeed on retry —
         * the bytes don't change. Skip retries and route straight to DLT.
         */
        errorHandler.addNotRetryableExceptions(JacksonException.class);

        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler kafkaErrorHandler,
            MdcKafkaRecordInterceptor mdcRecordInterceptor
    ) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.setRecordInterceptor(mdcRecordInterceptor);
        return factory;
    }
}
