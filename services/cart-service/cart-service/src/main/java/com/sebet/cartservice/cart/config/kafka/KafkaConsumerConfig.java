package com.sebet.cartservice.cart.config.kafka;

import com.sebet.cartservice.cart.metrics.KafkaMetrics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@Slf4j
@Configuration
public class KafkaConsumerConfig {

    private final KafkaMetrics kafkaMetrics;

    public KafkaConsumerConfig(KafkaMetrics kafkaMetrics) {
        this.kafkaMetrics = kafkaMetrics;
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            KafkaTemplate<Object, Object> kafkaTemplate
    ) {
        // ThreadLocal carries the failure reason from the destination resolver into
        // publish() so the metric tag is available at the point of confirmed send.
        // The resolver and publish() always run on the same Kafka consumer thread,
        // so ThreadLocal is safe here. The finally block in publish() guarantees cleanup.
        ThreadLocal<String> pendingReason = new ThreadLocal<>();

        return new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> {
                    // Store reason for publish() — counter is intentionally NOT fired here.
                    // recordDltPublished fires only after super.publish() succeeds,
                    // ensuring it counts real successful DLT publishes, not just routing decisions.
                    pendingReason.set(exception instanceof JacksonException
                            ? "parse_error"
                            : "processing_error");

                    /*
                     * Send failed message to same partition number in DLT.
                     * So product-events partition 0 goes to product-events.DLT partition 0.
                     */
                    return new TopicPartition(record.topic() + ".DLT", record.partition());
                }
        ) {
            /**
             * publish() is the point where the ProducerRecord is actually sent to the
             * broker. Overriding it is the only reliable hook in Spring Kafka 4.0 for
             * detecting DLT send success or failure — there is no setDltSendFailed()
             * callback in this version.
             *
             * recordDltPublished fires only when super.publish() returns without throwing,
             * meaning the broker confirmed receipt of the DLT message.
             * recordDltSendFailed fires when super.publish() throws — the message is
             * permanently lost: not processed AND not in the DLT.
             */
            @Override
            protected void publish(
                    ProducerRecord<Object, Object> outRecord,
                    KafkaOperations<Object, Object> kafkaOperations,
                    ConsumerRecord<?, ?> inRecord
            ) {
                try {
                    super.publish(outRecord, kafkaOperations, inRecord);
                    kafkaMetrics.recordDltPublished(inRecord.topic(), pendingReason.get());
                    log.info(
                            "Kafka message successfully published to DLT. originalTopic={}, dltTopic={}, partition={}, offset={}, key={}, value={}, reason={}",
                            inRecord.topic(),
                            outRecord.topic(),
                            inRecord.partition(),
                            inRecord.offset(),
                            inRecord.key(),
                            inRecord.value(),
                            pendingReason.get()
                    );
                } catch (Exception e) {
                    kafkaMetrics.recordDltSendFailed(inRecord.topic());
                    log.error(
                            "Failed to send message to DLT — message permanently lost. originalTopic={}, partition={}, offset={}, key={}, value={}",
                            inRecord.topic(),
                            inRecord.partition(),
                            inRecord.offset(),
                            inRecord.key(),
                            inRecord.value(),
                            e
                    );
                    throw e;
                } finally {
                    pendingReason.remove();
                }
            }
        };
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
