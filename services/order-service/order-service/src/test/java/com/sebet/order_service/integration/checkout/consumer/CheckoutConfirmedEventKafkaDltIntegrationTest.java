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
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CheckoutConfirmedEventKafkaDltIntegrationTest {

    private static final String TOPIC = "checkout-events-dlt-it";
    private static final String DLT_TOPIC = "checkout-events-dlt-it.DLT";

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
        registry.add("order-service.kafka.checkout-events.group-id", () -> "order-service-dlt-it");
        registry.add("order-service.kafka.checkout-events.auto-startup", () -> "true");
        registry.add("order-service.kafka.checkout-events.dlt-topic", () -> DLT_TOPIC);
        registry.add("order-service.kafka.checkout-events.retry.initial-interval-ms", () -> "100");
        registry.add("order-service.kafka.checkout-events.retry.multiplier", () -> "1.0");
        registry.add("order-service.kafka.checkout-events.retry.max-interval-ms", () -> "100");
        registry.add("order-service.kafka.checkout-events.retry.max-attempts", () -> "2");
    }

    @Autowired
    private KafkaTemplate<String, CheckoutConfirmedEvent> kafkaTemplate;

    @MockitoBean
    private OrderCreationService orderCreationService;

    @MockitoBean
    private OrderLockRedisRepository orderLockRedisRepository;

    @BeforeEach
    void allowCheckoutLock() {
        when(orderLockRedisRepository.tryAcquire(any(), any())).thenReturn(true);
        when(orderLockRedisRepository.release(any(), any())).thenReturn(true);
    }

    @Test
    void sendsFailedCheckoutEventToDltAfterRetriesAreExhausted() throws Exception {
        CheckoutConfirmedEvent event = checkoutEvent("cart-kafka-it-retryable");
        when(orderCreationService.createOrder(any(CreateOrderCommand.class)))
                .thenThrow(new IllegalStateException("database temporarily unavailable"));

        kafkaTemplate.send(TOPIC, 0, event.cartId(), event).get(10, TimeUnit.SECONDS);
        kafkaTemplate.flush();

        ConsumerRecord<String, String> dltRecord = awaitDltRecord(
                "checkout-dlt-test-" + System.nanoTime(),
                event.cartId(),
                Duration.ofSeconds(15)
        );

        assertThat(dltRecord.key()).isEqualTo(event.cartId());
        assertThat(dltRecord.partition()).isZero();
        assertThat(dltRecord.value()).contains("\"cartId\":\"cart-kafka-it-retryable\"");
        verify(orderCreationService, times(3)).createOrder(any(CreateOrderCommand.class));
    }

    @Test
    void sendsNonRetryableExceptionToDltWithoutRetries() throws Exception {
        CheckoutConfirmedEvent event = checkoutEvent("cart-kafka-it-non-retryable");
        when(orderCreationService.createOrder(any(CreateOrderCommand.class)))
                .thenThrow(new IllegalArgumentException("invalid checkout event"));

        kafkaTemplate.send(TOPIC, 0, event.cartId(), event).get(10, TimeUnit.SECONDS);
        kafkaTemplate.flush();

        ConsumerRecord<String, String> dltRecord = awaitDltRecord(
                "checkout-non-retryable-dlt-test-" + System.nanoTime(),
                event.cartId(),
                Duration.ofSeconds(15)
        );

        assertThat(dltRecord.key()).isEqualTo(event.cartId());
        assertThat(dltRecord.partition()).isZero();
        assertThat(dltRecord.value()).contains("\"cartId\":\"cart-kafka-it-non-retryable\"");
        verify(orderCreationService, times(1)).createOrder(any(CreateOrderCommand.class));
    }

    @Test
    void sendsMalformedJsonToDltThroughErrorHandlingDeserializer() {
        String cartId = "cart-kafka-it-malformed-json";

        sendMalformedJson(1, cartId);

        ConsumerRecord<String, String> dltRecord = awaitDltRecord(
                "checkout-malformed-dlt-test-" + System.nanoTime(),
                cartId,
                Duration.ofSeconds(15)
        );

        assertThat(dltRecord.key()).isEqualTo(cartId);
        assertThat(dltRecord.partition()).isEqualTo(1);
        verify(orderCreationService, times(0)).createOrder(any(CreateOrderCommand.class));
    }

    private ConsumerRecord<String, String> awaitDltRecord(
            String groupId,
            String cartId,
            Duration timeout
    ) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(Collections.singletonList(DLT_TOPIC));

            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
                for (ConsumerRecord<String, String> record : records) {
                    if (cartId.equals(record.key())) {
                        return record;
                    }
                }
            }
        }

        fail("Timed out waiting for DLT record with cartId " + cartId);
        throw new IllegalStateException("unreachable");
    }

    private void sendMalformedJson(int partition, String key) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
            producer.send(new ProducerRecord<>(TOPIC, partition, key, "{not-json")).get(10, TimeUnit.SECONDS);
            producer.flush();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to send malformed JSON test record", exception);
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
        NewTopic checkoutEventsDltIntegrationTopic() {
            return TopicBuilder.name(TOPIC).partitions(2).replicas(1).build();
        }

        @Bean
        NewTopic checkoutEventsDltIntegrationDltTopic() {
            return TopicBuilder.name(DLT_TOPIC).partitions(2).replicas(1).build();
        }
    }
}
