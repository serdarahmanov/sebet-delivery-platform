package com.sebet.order_service.integration.checkout.consumer;

import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedEvent;
import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedItem;
import com.sebet.order_service.integration.checkout.event.CheckoutDeliveryAddress;
import com.sebet.order_service.integration.checkout.event.CheckoutStoreLocation;
import com.sebet.order_service.cache.repository.OrderLockRedisRepository;
import com.sebet.order_service.order.command.CreateOrderCommand;
import com.sebet.order_service.order.service.OrderCreationService;
import com.sebet.order_service.shared.enums.ProductUnit;
import com.sebet.order_service.shared.enums.ScheduleType;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CheckoutConfirmedEventKafkaDltPublishFailureIntegrationTest {

    private static final String TOPIC = "checkout-events-dlt-publish-failure-it";
    private static final String DLT_TOPIC = "checkout-events-dlt-publish-failure-it.DLT";
    private static final String GROUP_ID = "order-service-dlt-publish-failure-it";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
    );

    @Container
    static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("apache/kafka-native:3.8.0")
    );

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("spring.kafka.producer.key-serializer",
                () -> "org.apache.kafka.common.serialization.StringSerializer");
        registry.add("spring.kafka.producer.value-serializer",
                () -> "org.springframework.kafka.support.serializer.JsonSerializer");
        registry.add("order-service.kafka.checkout-events.topic", () -> TOPIC);
        registry.add("order-service.kafka.checkout-events.group-id", () -> GROUP_ID);
        registry.add("order-service.kafka.checkout-events.auto-startup", () -> "true");
        registry.add("order-service.kafka.checkout-events.dlt-topic", () -> DLT_TOPIC);
        registry.add("order-service.kafka.checkout-events.retry.initial-interval-ms", () -> "100");
        registry.add("order-service.kafka.checkout-events.retry.multiplier", () -> "1.0");
        registry.add("order-service.kafka.checkout-events.retry.max-interval-ms", () -> "100");
        registry.add("order-service.kafka.checkout-events.retry.max-attempts", () -> "1");
    }

    @Autowired
    private KafkaTemplate<String, CheckoutConfirmedEvent> kafkaTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @MockitoBean
    private OrderCreationService orderCreationService;

    @MockitoBean
    private OrderLockRedisRepository orderLockRedisRepository;

    @MockitoBean
    private DeadLetterPublishingRecoverer deadLetterPublishingRecoverer;

    @BeforeEach
    void allowCheckoutLock() {
        when(orderLockRedisRepository.tryAcquire(any(), any())).thenReturn(true);
        when(orderLockRedisRepository.release(any(), any())).thenReturn(true);
    }

    @Test
    void doesNotCommitSourceOffsetWhenDltPublishFails() throws Exception {
        CheckoutConfirmedEvent event = checkoutEvent("cart-kafka-it-dlt-publish-fails");
        when(orderCreationService.createOrder(any(CreateOrderCommand.class)))
                .thenThrow(new IllegalStateException("database temporarily unavailable"));
        doThrow(new IllegalStateException("DLT broker unavailable"))
                .when(deadLetterPublishingRecoverer)
                .accept(any(ConsumerRecord.class), any(Consumer.class), any(Exception.class));

        kafkaTemplate.send(TOPIC, 1, event.cartId(), event).get(10, TimeUnit.SECONDS);
        kafkaTemplate.flush();

        verify(orderCreationService, timeout(10_000).atLeast(2))
                .createOrder(any(CreateOrderCommand.class));
        verify(deadLetterPublishingRecoverer, timeout(10_000).atLeast(1))
                .accept(any(ConsumerRecord.class), any(Consumer.class), any(Exception.class));
        kafkaListenerEndpointRegistry.stop();

        Long committedOffset = committedOffsetForSourcePartition(1);

        assertThat(committedOffset).isIn(null, 0L);
        verify(orderCreationService, atLeast(2)).createOrder(any(CreateOrderCommand.class));
    }

    private Long committedOffsetForSourcePartition(int partition) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            TopicPartition topicPartition = new TopicPartition(TOPIC, partition);
            org.apache.kafka.clients.consumer.OffsetAndMetadata committed = consumer
                    .committed(java.util.Set.of(topicPartition))
                    .get(topicPartition);
            return committed == null ? null : committed.offset();
        }
    }

    private CheckoutConfirmedEvent checkoutEvent(String cartId) {
        return new CheckoutConfirmedEvent(
                cartId,
                "customer-1",
                "store-1",
                ScheduleType.IMMEDIATE,
                null,
                new BigDecimal("33000.00"),
                new BigDecimal("2000.00"),
                new BigDecimal("3000.00"),
                new BigDecimal("8000.00"),
                new BigDecimal("36000.00"),
                "UZS",
                new CheckoutDeliveryAddress(
                        "Amir Temur 25",
                        "Tashkent",
                        new BigDecimal("41.311100"),
                        new BigDecimal("69.279700"),
                        "42",
                        "2",
                        "5",
                        "Call before arrival"
                ),
                new CheckoutStoreLocation(
                        new BigDecimal("41.320100"),
                        new BigDecimal("69.240500")
                ),
                List.of(new CheckoutConfirmedItem(
                        "product-1",
                        "Apples",
                        new BigDecimal("2.000"),
                        ProductUnit.KG,
                        new BigDecimal("12000.00"),
                        new BigDecimal("24000.00"),
                        new BigDecimal("2000.00"),
                        new BigDecimal("22000.00"),
                        "https://cdn.sebet.test/products/apple.png"
                ))
        );
    }

    @TestConfiguration
    static class KafkaTopicTestConfig {

        @Bean
        NewTopic checkoutEventsDltFailureIntegrationTopic() {
            return TopicBuilder.name(TOPIC).partitions(2).replicas(1).build();
        }

        @Bean
        NewTopic checkoutEventsDltFailureIntegrationDltTopic() {
            return TopicBuilder.name(DLT_TOPIC).partitions(1).replicas(1).build();
        }
    }
}
