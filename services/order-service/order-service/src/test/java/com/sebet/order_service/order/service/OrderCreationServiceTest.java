package com.sebet.order_service.order.service;

import com.sebet.order_service.cache.repository.ActiveOrdersRedisRepository;
import com.sebet.order_service.cache.repository.OrderRedisRepository;
import com.sebet.order_service.cache.repository.OrderStatusRedisRepository;
import com.sebet.order_service.cache.repository.OrderTimelineRedisRepository;
import com.sebet.order_service.cache.repository.StoreActiveOrdersRedisRepository;
import com.sebet.order_service.cache.repository.StoreScheduledOrdersRedisRepository;
import com.sebet.order_service.cache.dto.OrderTimelineEntry;
import com.sebet.order_service.order.command.CreateOrderCommand;
import com.sebet.order_service.order.command.CreateOrderItemCommand;
import com.sebet.order_service.order.command.CreateOrderResult;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderItemEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.repository.OrderItemRepository;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ProductUnit;
import com.sebet.order_service.shared.enums.ScheduleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OrderCreationServiceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
    );

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")
    ).withExposedPorts(6379);

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Autowired
    private OrderCreationService orderCreationService;

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void clearState() {
        orderStatusHistoryRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.flushDb();
            return null;
        });
    }

    @Test
    void createsImmediateOrderAsPending() {
        CreateOrderCommand command = command("cart-immediate-1", ScheduleType.IMMEDIATE, null);

        CreateOrderResult result = orderCreationService.createOrder(command);

        assertThat(result.createdNewOrder()).isTrue();
        assertThat(result.order().getStatus()).isEqualTo(OrderStatus.PENDING);

        OrderEntity savedOrder = orderRepository.findByCartId("cart-immediate-1").orElseThrow();
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(savedOrder.getSubtotalAmount()).isEqualByComparingTo(new BigDecimal("33000.00"));
        assertThat(savedOrder.getItemDiscountAmount()).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(savedOrder.getOrderDiscountAmount()).isEqualByComparingTo(new BigDecimal("3000.00"));
        assertThat(savedOrder.getTotalAmount()).isEqualByComparingTo(new BigDecimal("36000.00"));

        assertThat(orderItemRepository.findByOrderIdOrderByLineNumberAsc(savedOrder.getId()))
                .extracting(OrderItemEntity::getLineNumber)
                .containsExactly(1, 2);

        assertThat(orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(savedOrder.getId()))
                .extracting(OrderStatusHistoryEntity::getFromStatus, OrderStatusHistoryEntity::getToStatus)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(null, OrderStatus.PENDING));

        assertThat(orderStatusRedisRepository.findById(savedOrder.getId().toString()).map(OrderStatusRedisRepository.Entry::status))
                .hasValue(OrderStatus.PENDING.name());

        assertThat(orderTimelineRedisRepository.findAll(savedOrder.getId().toString()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getStatus()).isEqualTo("PLACED");
                    assertThat(OffsetDateTime.parse(entry.getOccurredAt()).toInstant())
                            .isCloseTo(savedOrder.getCreatedAt().toInstant(), within(1, ChronoUnit.MILLIS));
                });

        assertThat(orderRedisRepository.findById(savedOrder.getId().toString()))
                .hasValueSatisfying(snapshot -> {
                    assertThat(snapshot.getOrderId()).isEqualTo(savedOrder.getId().toString());
                    assertThat(snapshot.getUserId()).isEqualTo("customer-1");
                    assertThat(snapshot.getStoreId()).isEqualTo("store-1");
                    assertThat(snapshot.getCartId()).isEqualTo("cart-immediate-1");
                    assertThat(snapshot.getTotalAmount()).isEqualByComparingTo(new BigDecimal("36000.00"));
                    assertThat(snapshot.getEstimatedDeliveryAt()).isNull();
                    assertThat(snapshot.getDeliveryAddress().getStreet()).isEqualTo("Amir Temur 25");
                    assertThat(snapshot.getDeliveryAddress().getCity()).isEqualTo("Tashkent");
                    assertThat(snapshot.getDeliveryAddress().getLat()).isEqualTo(41.311100d);
                    assertThat(snapshot.getDeliveryAddress().getLng()).isEqualTo(69.279700d);
                    assertThat(snapshot.getStoreLocation().getLat()).isEqualTo(41.320100d);
                    assertThat(snapshot.getStoreLocation().getLng()).isEqualTo(69.240500d);
                    assertThat(snapshot.getItems()).hasSize(2);
                    assertThat(snapshot.getItems().get(0).getProductId()).isEqualTo("product-1");
                    assertThat(snapshot.getItems().get(0).getName()).isEqualTo("Apples");
                    assertThat(snapshot.getItems().get(0).getQuantity()).isEqualByComparingTo("2.500");
                });

        assertThat(activeOrdersRedisRepository.contains("customer-1", savedOrder.getId().toString())).isTrue();
        assertThat(storeActiveOrdersRedisRepository.contains("store-1", savedOrder.getId().toString())).isTrue();
        assertThat(storeScheduledOrdersRedisRepository.contains("store-1", savedOrder.getId().toString())).isFalse();
    }

    @Test
    void createsScheduledOrderAsScheduled() {
        OffsetDateTime scheduledFor = OffsetDateTime.parse("2026-07-05T10:00:00Z");
        CreateOrderCommand command = command("cart-scheduled-1", ScheduleType.SCHEDULED, scheduledFor);

        CreateOrderResult result = orderCreationService.createOrder(command);

        assertThat(result.createdNewOrder()).isTrue();
        assertThat(result.order().getStatus()).isEqualTo(OrderStatus.SCHEDULED);
        assertThat(result.order().getScheduledFor()).isEqualTo(scheduledFor);

        List<OrderStatusHistoryEntity> history = orderStatusHistoryRepository
                .findByOrderIdOrderByCreatedAtAsc(result.order().getId());
        assertThat(history)
                .singleElement()
                .extracting(OrderStatusHistoryEntity::getToStatus)
                .isEqualTo(OrderStatus.SCHEDULED);

        assertThat(orderStatusRedisRepository.findById(result.order().getId().toString()).map(OrderStatusRedisRepository.Entry::status))
                .hasValue(OrderStatus.SCHEDULED.name());

        assertThat(orderTimelineRedisRepository.findAll(result.order().getId().toString()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getStatus()).isEqualTo("PLACED");
                    assertThat(OffsetDateTime.parse(entry.getOccurredAt()).toInstant())
                            .isCloseTo(result.order().getCreatedAt().toInstant(), within(1, ChronoUnit.MILLIS));
                });

        assertThat(orderRedisRepository.findById(result.order().getId().toString()))
                .hasValueSatisfying(snapshot -> {
                    assertThat(snapshot.getEstimatedDeliveryAt()).isEqualTo(scheduledFor.toString());
                    assertThat(snapshot.getCartId()).isEqualTo("cart-scheduled-1");
                });

        assertThat(activeOrdersRedisRepository.contains("customer-1", result.order().getId().toString())).isFalse();
        assertThat(storeActiveOrdersRedisRepository.contains("store-1", result.order().getId().toString())).isFalse();
        assertThat(storeScheduledOrdersRedisRepository.contains("store-1", result.order().getId().toString())).isTrue();
    }

    @Test
    void duplicateCartIdReturnsExistingOrderWithoutCreatingRows() {
        CreateOrderCommand command = command("cart-duplicate-1", ScheduleType.IMMEDIATE, null);

        CreateOrderResult firstResult = orderCreationService.createOrder(command);
        CreateOrderResult secondResult = orderCreationService.createOrder(command);

        assertThat(firstResult.createdNewOrder()).isTrue();
        assertThat(secondResult.createdNewOrder()).isFalse();
        assertThat(secondResult.order().getId()).isEqualTo(firstResult.order().getId());
        assertThat(orderRepository.findAll()).hasSize(1);
        assertThat(orderItemRepository.findByOrderIdOrderByLineNumberAsc(firstResult.order().getId())).hasSize(2);
        assertThat(orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(firstResult.order().getId())).hasSize(1);
        assertThat(orderRedisRepository.findById(firstResult.order().getId().toString())).isPresent();
        assertThat(orderStatusRedisRepository.findById(firstResult.order().getId().toString()).map(OrderStatusRedisRepository.Entry::status))
                .hasValue(OrderStatus.PENDING.name());
        assertThat(orderTimelineRedisRepository.findAll(firstResult.order().getId().toString())).hasSize(1);
        assertThat(activeOrdersRedisRepository.count("customer-1")).isEqualTo(1L);
        assertThat(storeActiveOrdersRedisRepository.count("store-1")).isEqualTo(1L);
    }

    @Test
    void duplicateCartIdRepairsMissingRedisViews() {
        CreateOrderCommand command = command("cart-repair-1", ScheduleType.IMMEDIATE, null);

        CreateOrderResult firstResult = orderCreationService.createOrder(command);
        String orderId = firstResult.order().getId().toString();

        orderRedisRepository.delete(orderId);
        orderStatusRedisRepository.delete(orderId);
        orderTimelineRedisRepository.delete(orderId);
        activeOrdersRedisRepository.remove("customer-1", orderId);
        storeActiveOrdersRedisRepository.remove("store-1", orderId);

        assertThat(orderRedisRepository.findById(orderId)).isEmpty();
        assertThat(orderStatusRedisRepository.findById(orderId)).isEmpty();
        assertThat(orderTimelineRedisRepository.findAll(orderId)).isEmpty();
        assertThat(activeOrdersRedisRepository.contains("customer-1", orderId)).isFalse();
        assertThat(storeActiveOrdersRedisRepository.contains("store-1", orderId)).isFalse();

        CreateOrderResult secondResult = orderCreationService.createOrder(command);

        assertThat(secondResult.createdNewOrder()).isFalse();
        assertThat(orderRedisRepository.findById(orderId)).isPresent();
        assertThat(orderStatusRedisRepository.findById(orderId).map(OrderStatusRedisRepository.Entry::status)).hasValue(OrderStatus.PENDING.name());
        assertThat(orderTimelineRedisRepository.findAll(orderId)).hasSize(1);
        assertThat(activeOrdersRedisRepository.contains("customer-1", orderId)).isTrue();
        assertThat(storeActiveOrdersRedisRepository.contains("store-1", orderId)).isTrue();
    }

    @Test
    void duplicateCartIdUsesCurrentDatabaseStateWhenOrderHasAdvanced() {
        CreateOrderCommand command = command("cart-late-duplicate-1", ScheduleType.IMMEDIATE, null);

        CreateOrderResult firstResult = orderCreationService.createOrder(command);
        OrderEntity order = orderRepository.findById(firstResult.order().getId()).orElseThrow();
        OffsetDateTime baseTime = firstResult.order().getCreatedAt();

        order.setStatus(OrderStatus.DELIVERED);
        order.setDeliveredAt(OffsetDateTime.parse("2026-07-05T12:30:00Z"));
        orderRepository.saveAndFlush(order);

        orderStatusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(order.getId())
                .fromStatus(OrderStatus.PENDING)
                .toStatus(OrderStatus.CONFIRMED)
                .changedByType("STORE")
                .createdAt(baseTime.plusMinutes(5))
                .build());
        orderStatusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(order.getId())
                .fromStatus(OrderStatus.CONFIRMED)
                .toStatus(OrderStatus.READY_FOR_PICKUP)
                .changedByType("STORE")
                .createdAt(baseTime.plusMinutes(10))
                .build());
        orderStatusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(order.getId())
                .fromStatus(OrderStatus.READY_FOR_PICKUP)
                .toStatus(OrderStatus.OUT_FOR_DELIVERY)
                .changedByType("DRIVER")
                .createdAt(baseTime.plusMinutes(15))
                .build());
        orderStatusHistoryRepository.saveAndFlush(OrderStatusHistoryEntity.builder()
                .orderId(order.getId())
                .fromStatus(OrderStatus.OUT_FOR_DELIVERY)
                .toStatus(OrderStatus.DELIVERED)
                .changedByType("DRIVER")
                .createdAt(baseTime.plusMinutes(30))
                .build());

        String orderId = order.getId().toString();
        orderRedisRepository.delete(orderId);
        orderStatusRedisRepository.delete(orderId);
        orderTimelineRedisRepository.delete(orderId);
        activeOrdersRedisRepository.remove("customer-1", orderId);
        storeActiveOrdersRedisRepository.remove("store-1", orderId);

        CreateOrderResult secondResult = orderCreationService.createOrder(command);

        assertThat(secondResult.createdNewOrder()).isFalse();
        assertThat(orderStatusRedisRepository.findById(orderId).map(OrderStatusRedisRepository.Entry::status))
                .hasValue(OrderStatus.DELIVERED.name());
        assertThat(orderTimelineRedisRepository.findAll(orderId))
                .extracting(OrderTimelineEntry::getStatus)
                .containsExactly("PLACED", "PACKED", "ON_THE_WAY", "ARRIVED");
        assertThat(activeOrdersRedisRepository.contains("customer-1", orderId)).isFalse();
        assertThat(storeActiveOrdersRedisRepository.contains("store-1", orderId)).isFalse();
        assertThat(storeScheduledOrdersRedisRepository.contains("store-1", orderId)).isFalse();
    }

    private CreateOrderCommand command(String cartId, ScheduleType scheduleType, OffsetDateTime scheduledFor) {
        return new CreateOrderCommand(
                cartId,
                "customer-1",
                "store-1",
                scheduleType,
                scheduledFor,
                new BigDecimal("33000.00"),
                new BigDecimal("2000.00"),
                new BigDecimal("3000.00"),
                new BigDecimal("8000.00"),
                new BigDecimal("36000.00"),
                "UZS",
                "{\"street\":\"Amir Temur 25\",\"city\":\"Tashkent\"}",
                new BigDecimal("41.311100"),
                new BigDecimal("69.279700"),
                new BigDecimal("41.320100"),
                new BigDecimal("69.240500"),
                List.of(
                        new CreateOrderItemCommand(
                                "product-1",
                                "Apples",
                                new BigDecimal("2.500"),
                                ProductUnit.KG,
                                new BigDecimal("12000.00"),
                                new BigDecimal("24000.00"),
                                new BigDecimal("2000.00"),
                                new BigDecimal("22000.00"),
                                "https://cdn.sebet.test/products/apple.png"
                        ),
                        new CreateOrderItemCommand(
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
