package com.sebet.order_service.integration.checkout.consumer;

import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedEvent;
import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedItem;
import com.sebet.order_service.integration.checkout.event.CheckoutDeliveryAddress;
import com.sebet.order_service.integration.checkout.event.CheckoutStoreLocation;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderItemEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.repository.OrderItemRepository;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ProductUnit;
import com.sebet.order_service.shared.enums.ScheduleType;
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
import java.util.List;
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
                () -> "org.springframework.kafka.support.serializer.JsonSerializer");
        registry.add("order-service.kafka.checkout-events.topic", () -> TOPIC);
        registry.add("order-service.kafka.checkout-events.group-id", () -> "order-service-it");
        registry.add("order-service.kafka.checkout-events.auto-startup", () -> "true");
    }

    @Autowired
    private KafkaTemplate<String, CheckoutConfirmedEvent> kafkaTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Test
    void consumesCheckoutConfirmedEventFromKafkaAndCreatesOrder() throws Exception {
        CheckoutConfirmedEvent event = checkoutEvent("cart-kafka-it-1");

        kafkaTemplate.send(TOPIC, event.cartId(), event).get(10, TimeUnit.SECONDS);
        kafkaTemplate.flush();

        OrderEntity order = awaitOrderByCartId(event.cartId(), Duration.ofSeconds(15));

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
                List.of(
                        new CheckoutConfirmedItem(
                                "product-1",
                                "Apples",
                                new BigDecimal("2.000"),
                                ProductUnit.KG,
                                new BigDecimal("12000.00"),
                                new BigDecimal("24000.00"),
                                new BigDecimal("2000.00"),
                                new BigDecimal("22000.00"),
                                "https://cdn.sebet.test/products/apple.png"
                        ),
                        new CheckoutConfirmedItem(
                                "product-2",
                                "Milk",
                                new BigDecimal("1.000"),
                                ProductUnit.PCS,
                                new BigDecimal("9000.00"),
                                new BigDecimal("9000.00"),
                                BigDecimal.ZERO,
                                new BigDecimal("9000.00"),
                                null
                        )
                )
        );
    }

}
