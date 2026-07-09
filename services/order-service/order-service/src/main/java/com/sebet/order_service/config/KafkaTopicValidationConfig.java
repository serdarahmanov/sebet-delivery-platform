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
    private final OrderEventsKafkaProperties orderEventsProperties;

    @Bean
    @ConditionalOnProperty(
            prefix = "order-service.kafka.checkout-events",
            name = "validate-topics",
            havingValue = "true"
    )
    public ApplicationRunner checkoutDltTopicValidator() {
        return args -> {
            try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
                validateDltTopic(adminClient, properties.getTopic(), properties.getDltTopic());
            }
        };
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "order-service.kafka.order-events",
            name = "validate-topics",
            havingValue = "true"
    )
    public ApplicationRunner orderEventsDltTopicValidator() {
        return args -> {
            try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
                validateDltTopic(
                        adminClient,
                        orderEventsProperties.getTopic(),
                        orderEventsProperties.getDltTopic()
                );
            }
        };
    }

    private void validateDltTopic(AdminClient adminClient, String sourceTopic, String dltTopic) throws Exception {
        Map<String, TopicDescription> topics = adminClient.describeTopics(
                java.util.List.of(sourceTopic, dltTopic)
        ).allTopicNames().get(10, TimeUnit.SECONDS);

        int sourcePartitions = topics.get(sourceTopic).partitions().size();
        int dltPartitions = topics.get(dltTopic).partitions().size();
        if (dltPartitions < sourcePartitions) {
            throw new IllegalStateException(
                    "DLT topic " + dltTopic
                            + " has fewer partitions (" + dltPartitions + ") than source topic "
                            + sourceTopic + " (" + sourcePartitions + ")"
            );
        }
    }
}
