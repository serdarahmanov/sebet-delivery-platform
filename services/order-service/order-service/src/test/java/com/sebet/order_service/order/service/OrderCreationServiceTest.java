package com.sebet.order_service.order.service;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class OrderCreationServiceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
    );

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
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
                                new BigDecimal("2.000"),
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
