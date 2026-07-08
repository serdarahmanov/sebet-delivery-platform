package com.sebet.order_service.integration.checkout.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.OrderTimelineEntry;
import com.sebet.order_service.cache.repository.ActiveOrdersRedisRepository;
import com.sebet.order_service.cache.repository.OrderRedisRepository;
import com.sebet.order_service.cache.repository.OrderStatusRedisRepository;
import com.sebet.order_service.cache.repository.OrderTimelineRedisRepository;
import com.sebet.order_service.cache.repository.StoreActiveOrdersRedisRepository;
import com.sebet.order_service.cache.repository.StoreScheduledOrdersRedisRepository;
import com.sebet.order_service.integration.checkout.CheckoutEventTestFactory;
import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedPayload;
import com.sebet.order_service.integration.checkout.event.IntegrationEvent;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderItemEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.repository.OrderItemRepository;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CheckoutConfirmedEventKafkaIntegrationTest {

    private static final String TOPIC = "checkout-events-it";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
    );

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")
    ).withExposedPorts(6379);

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
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("spring.kafka.producer.key-serializer",
                () -> "org.apache.kafka.common.serialization.StringSerializer");
        registry.add("spring.kafka.producer.value-serializer",
                () -> "org.apache.kafka.common.serialization.StringSerializer");
        registry.add("order-service.internal.secret", () -> "test-internal-secret");
        registry.add("order-service.kafka.checkout-events.topic", () -> TOPIC);
        registry.add("order-service.kafka.checkout-events.group-id", () -> "order-service-it");
        registry.add("order-service.kafka.checkout-events.auto-startup", () -> "true");
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Autowired
    private OrderRedisRepository orderRedisRepository;

    @Autowired
    private OrderStatusRedisRepository orderStatusRedisRepository;

    @Autowired
    private ActiveOrdersRedisRepository activeOrdersRedisRepository;

    @Autowired
    private StoreActiveOrdersRedisRepository storeActiveOrdersRedisRepository;

    @Autowired
    private StoreScheduledOrdersRedisRepository storeScheduledOrdersRedisRepository;

    @Autowired
    private OrderTimelineRedisRepository orderTimelineRedisRepository;

    @Test
    void consumesCheckoutConfirmedEventFromKafkaAndCreatesOrder() throws Exception {
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent("cart-kafka-it-1");

        kafkaTemplate.send(TOPIC, event.data().cartId(), objectMapper.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
        kafkaTemplate.flush();

        OrderEntity order = awaitOrderByCartId(event.data().cartId(), Duration.ofSeconds(15));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getCustomerId()).isEqualTo("customer-1");
        assertThat(order.getStoreId()).isEqualTo("store-1");
        assertThat(order.getTotalAmount()).isEqualByComparingTo(new BigDecimal("36000.00"));

        assertThat(orderItemRepository.findByOrderIdOrderByLineNumberAsc(order.getId()))
                .extracting(OrderItemEntity::getLineNumber, OrderItemEntity::getProductId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "product-1"),
                        org.assertj.core.groups.Tuple.tuple(2, "product-2")
                );
        assertThat(orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(order.getId()))
                .extracting(OrderStatusHistoryEntity::getFromStatus, OrderStatusHistoryEntity::getToStatus)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(null, OrderStatus.PENDING));

        assertThat(orderStatusRedisRepository.findById(order.getId().toString()))
                .hasValueSatisfying(entry -> {
                    assertThat(entry.status()).isEqualTo(OrderStatus.PENDING.name());
                    assertThat(entry.userId()).isEqualTo("customer-1");
                });

        assertThat(orderTimelineRedisRepository.findAll(order.getId().toString()))
                .singleElement()
                .extracting(OrderTimelineEntry::getStatus)
                .isEqualTo("PLACED");

        assertThat(orderRedisRepository.findById(order.getId().toString()))
                .hasValueSatisfying(snapshot -> {
                    assertThat(snapshot.getOrderId()).isEqualTo(order.getId().toString());
                    assertThat(snapshot.getUserId()).isEqualTo("customer-1");
                    assertThat(snapshot.getStoreId()).isEqualTo("store-1");
                });

        assertThat(activeOrdersRedisRepository.contains("customer-1", order.getId().toString())).isTrue();
        assertThat(storeActiveOrdersRedisRepository.contains("store-1", order.getId().toString())).isTrue();
        assertThat(storeScheduledOrdersRedisRepository.contains("store-1", order.getId().toString())).isFalse();
    }

    private OrderEntity awaitOrderByCartId(String cartId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<OrderEntity> order = orderRepository.findByCartId(cartId);
            if (order.isPresent()) {
                return order.get();
            }
            Thread.sleep(200);
        }
        fail("Timed out waiting for order with cartId " + cartId);
        throw new IllegalStateException("unreachable");
    }

}
