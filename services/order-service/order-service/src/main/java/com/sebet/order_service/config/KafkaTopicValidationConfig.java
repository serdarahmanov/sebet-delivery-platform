package com.sebet.order_service.config;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration
@RequiredArgsConstructor
public class KafkaTopicValidationConfig {

    private final KafkaAdmin kafkaAdmin;
    private final KafkaRetryProperties properties;

    @Bean
    @ConditionalOnProperty(
            prefix = "order-service.kafka.checkout-events",
            name = "validate-topics",
            havingValue = "true"
    )
    public ApplicationRunner checkoutDltTopicValidator() {
        return args -> {
            try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
                Map<String, TopicDescription> topics = adminClient.describeTopics(
                        java.util.List.of(properties.getTopic(), properties.getDltTopic())
                ).allTopicNames().get(10, TimeUnit.SECONDS);

                int sourcePartitions = topics.get(properties.getTopic()).partitions().size();
                int dltPartitions = topics.get(properties.getDltTopic()).partitions().size();
                if (dltPartitions < sourcePartitions) {
                    throw new IllegalStateException(
                            "DLT topic " + properties.getDltTopic()
                                    + " has fewer partitions (" + dltPartitions + ") than source topic "
                                    + properties.getTopic() + " (" + sourcePartitions + ")"
                    );
                }
            }
        };
    }
}
