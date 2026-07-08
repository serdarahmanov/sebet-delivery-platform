package com.sebet.order_service.persistence.repository;

import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderItemEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.entity.OutboxEventEntity;
import com.sebet.order_service.persistence.entity.ProcessedEventEntity;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ProductUnit;
import com.sebet.order_service.shared.enums.ScheduleType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class OrderPersistenceRepositoryTest {

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
        registry.add("spring.jpa.show-sql", () -> "false");
    }

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savesOrderWithItemsAndStatusHistory() {
        OrderEntity order = orderRepository.saveAndFlush(newOrder(
                UUID.randomUUID(),
                "customer-1",
                "store-1",
                OrderStatus.PENDING,
                OffsetDateTime.parse("2026-07-04T10:00:00Z")
        ));

        OrderItemEntity firstItem = orderItemRepository.save(OrderItemEntity.builder()
                .orderId(order.getId())
                .lineNumber(1)
                .productId("product-1")
                .productName("Apples")
                .quantity(new BigDecimal("2.000"))
                .unit(ProductUnit.KG)
                .unitPriceAmount(new BigDecimal("12000.00"))
                .grossAmount(new BigDecimal("24000.00"))
                .discountAmount(new BigDecimal("2000.00"))
                .netAmount(new BigDecimal("22000.00"))
                .imageUrl("https://cdn.sebet.test/products/apple.png")
                .createdAt(OffsetDateTime.parse("2026-07-04T10:01:00Z"))
                .build());
        OrderItemEntity secondItem = orderItemRepository.save(OrderItemEntity.builder()
                .orderId(order.getId())
                .lineNumber(2)
                .productId("product-2")
                .productName("Milk")
                .quantity(new BigDecimal("1.000"))
                .unit(ProductUnit.PCS)
                .unitPriceAmount(new BigDecimal("9000.00"))
                .grossAmount(new BigDecimal("9000.00"))
                .discountAmount(BigDecimal.ZERO)
                .netAmount(new BigDecimal("9000.00"))
                .createdAt(OffsetDateTime.parse("2026-07-04T10:02:00Z"))
                .build());

        orderStatusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(order.getId())
                .fromStatus(null)
                .toStatus(OrderStatus.PENDING)
                .changedByType("SYSTEM")
                .reason("CHECKOUT_CONFIRMED")
                .metadataJson("{\"cartId\":\"cart-1\"}")
                .createdAt(OffsetDateTime.parse("2026-07-04T10:00:30Z"))
                .build());
        orderStatusHistoryRepository.saveAndFlush(OrderStatusHistoryEntity.builder()
                .orderId(order.getId())
                .fromStatus(OrderStatus.PENDING)
                .toStatus(OrderStatus.CONFIRMED)
                .changedByType("STORE")
                .changedById("store-1")
                .createdAt(OffsetDateTime.parse("2026-07-04T10:03:00Z"))
                .build());

        entityManager.clear();

        Optional<OrderEntity> savedOrderResult = orderRepository.findByIdAndCustomerId(order.getId(), "customer-1");
        assertThat(savedOrderResult).isPresent();
        OrderEntity savedOrder = savedOrderResult.get();
        assertThat(savedOrder.getDeliveryAddressJson())
                .contains("\"street\": \"Amir Temur 25\"")
                .contains("\"city\": \"Tashkent\"");
        assertThat(savedOrder.getDeliveryLat()).isEqualByComparingTo(new BigDecimal("41.311100"));
        assertThat(savedOrder.getDeliveryLng()).isEqualByComparingTo(new BigDecimal("69.279700"));
        assertThat(orderRepository.findByIdAndStoreId(order.getId(), "store-1")).isPresent();
        assertThat(orderRepository.findByCartId("cart-" + order.getId())).isPresent();

        assertThat(orderItemRepository.findByOrderIdOrderByLineNumberAsc(order.getId()))
                .extracting(OrderItemEntity::getId)
                .containsExactly(firstItem.getId(), secondItem.getId());

        assertThat(orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(order.getId()))
                .extracting(OrderStatusHistoryEntity::getToStatus)
                .containsExactly(OrderStatus.PENDING, OrderStatus.CONFIRMED);
    }

    @Test
    void findsCustomerAndStoreOrdersNewestFirst() {
        OrderEntity oldOrder = orderRepository.save(newOrder(
                UUID.randomUUID(),
                "customer-2",
                "store-2",
                OrderStatus.DELIVERED,
                OffsetDateTime.parse("2026-07-04T08:00:00Z")
        ));
        OrderEntity newOrder = orderRepository.saveAndFlush(newOrder(
                UUID.randomUUID(),
                "customer-2",
                "store-2",
                OrderStatus.CANCELLED,
                OffsetDateTime.parse("2026-07-04T09:00:00Z")
        ));

        assertThat(orderRepository.findByCustomerIdOrderByCreatedAtDesc("customer-2", PageRequest.of(0, 10)))
                .extracting(OrderEntity::getId)
                .containsExactly(newOrder.getId(), oldOrder.getId());
        assertThat(orderRepository.findByStoreIdOrderByCreatedAtDesc("store-2", PageRequest.of(0, 10)))
                .extracting(OrderEntity::getId)
                .containsExactly(newOrder.getId(), oldOrder.getId());
    }

    @Test
    void findsOrdersByOwnerAndStatus() {
        OrderEntity pendingOrder = orderRepository.save(newOrder(
                UUID.randomUUID(),
                "customer-3",
                "store-3",
                OrderStatus.PENDING,
                OffsetDateTime.parse("2026-07-04T08:00:00Z")
        ));
        OrderEntity confirmedOrder = orderRepository.save(newOrder(
                UUID.randomUUID(),
                "customer-3",
                "store-3",
                OrderStatus.CONFIRMED,
                OffsetDateTime.parse("2026-07-04T09:00:00Z")
        ));
        orderRepository.saveAndFlush(newOrder(
                UUID.randomUUID(),
                "customer-3",
                "store-3",
                OrderStatus.DELIVERED,
                OffsetDateTime.parse("2026-07-04T10:00:00Z")
        ));

        List<OrderStatus> activeStatuses = List.of(OrderStatus.PENDING, OrderStatus.CONFIRMED);

        assertThat(orderRepository.findByCustomerIdAndStatusInOrderByCreatedAtDesc("customer-3", activeStatuses))
                .extracting(OrderEntity::getId)
                .containsExactly(confirmedOrder.getId(), pendingOrder.getId());
        assertThat(orderRepository.findByStoreIdAndStatusInOrderByCreatedAtDesc("store-3", activeStatuses))
                .extracting(OrderEntity::getId)
                .containsExactly(confirmedOrder.getId(), pendingOrder.getId());
    }

    @Test
    void rejectsStaleOrderUpdateByVersion() {
        OrderEntity staleOrder = orderRepository.saveAndFlush(newOrder(
                UUID.randomUUID(),
                "customer-version",
                "store-version",
                OrderStatus.PENDING,
                OffsetDateTime.parse("2026-07-04T10:00:00Z")
        ));
        assertThat(staleOrder.getVersion()).isZero();
        entityManager.detach(staleOrder);

        OrderEntity freshOrder = orderRepository.findById(staleOrder.getId()).orElseThrow();
        freshOrder.setStatus(OrderStatus.CONFIRMED);
        orderRepository.saveAndFlush(freshOrder);
        assertThat(freshOrder.getVersion()).isEqualTo(1L);
        entityManager.detach(freshOrder);

        staleOrder.setStatus(OrderStatus.CANCELLED);
        assertThatThrownBy(() -> orderRepository.saveAndFlush(staleOrder))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void rejectsDuplicateProductInSameOrder() {
        OrderEntity order = orderRepository.saveAndFlush(newOrder(
                UUID.randomUUID(),
                "customer-products",
                "store-products",
                OrderStatus.PENDING,
                OffsetDateTime.parse("2026-07-04T10:00:00Z")
        ));

        orderItemRepository.saveAndFlush(newItem(order.getId(), 1, "product-duplicate"));

        assertThatThrownBy(() -> orderItemRepository.saveAndFlush(
                newItem(order.getId(), 2, "product-duplicate")
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateCartId() {
        OrderEntity firstOrder = newOrder(
                UUID.randomUUID(),
                "customer-5",
                "store-5",
                OrderStatus.PENDING,
                OffsetDateTime.parse("2026-07-04T10:00:00Z")
        );
        OrderEntity secondOrder = newOrder(
                UUID.randomUUID(),
                "customer-6",
                "store-6",
                OrderStatus.PENDING,
                OffsetDateTime.parse("2026-07-04T10:01:00Z")
        );
        secondOrder.setCartId(firstOrder.getCartId());

        orderRepository.saveAndFlush(firstOrder);

        assertThatThrownBy(() -> orderRepository.saveAndFlush(secondOrder))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void savesOutboxEventPayloadAsQueryableJsonb() {
        UUID eventId = UUID.randomUUID();
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-07-08T10:00:00Z");

        outboxEventRepository.saveAndFlush(OutboxEventEntity.builder()
                .id(eventId)
                .aggregateType("Order")
                .aggregateId("order-1")
                .eventType("OrderCreated")
                .eventKey("order-1")
                .payload("""
                        {
                          "eventId": "%s",
                          "eventType": "OrderCreated",
                          "aggregateId": "order-1",
                          "aggregateType": "Order",
                          "version": 1,
                          "occurredAt": "2026-07-08T10:00:00Z",
                          "source": "order-service",
                          "data": {
                            "orderId": "order-1",
                            "status": "PENDING"
                          }
                        }
                        """.formatted(eventId))
                .headers("{\"traceId\":\"trace-1\"}")
                .occurredAt(occurredAt)
                .build());

        entityManager.clear();

        OutboxEventEntity savedEvent = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(savedEvent.getAggregateType()).isEqualTo("Order");
        assertThat(savedEvent.getEventKey()).isEqualTo("order-1");
        assertThat(savedEvent.getOccurredAt()).isEqualTo(occurredAt);

        String eventType = jdbcTemplate.queryForObject(
                "select payload ->> 'eventType' from outbox_event where id = ?",
                String.class,
                eventId
        );
        String orderId = jdbcTemplate.queryForObject(
                "select payload #>> '{data,orderId}' from outbox_event where id = ?",
                String.class,
                eventId
        );
        assertThat(eventType).isEqualTo("OrderCreated");
        assertThat(orderId).isEqualTo("order-1");
    }

    @Test
    void outboxEventRequiresCoreColumns() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into outbox_event (
                            id, aggregate_id, event_type, event_key, payload, occurred_at
                        ) values (?, ?, ?, ?, ?::jsonb, ?)
                        """,
                UUID.randomUUID(),
                "order-1",
                "OrderCreated",
                "order-1",
                "{}",
                OffsetDateTime.parse("2026-07-08T10:00:00Z")
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void outboxEventMigrationCreatesExpectedIndexes() {
        List<String> indexNames = jdbcTemplate.queryForList(
                "select indexname from pg_indexes where schemaname = 'public' and tablename = 'outbox_event'",
                String.class
        );

        assertThat(indexNames).contains(
                "outbox_event_pkey",
                "idx_outbox_event_aggregate",
                "idx_outbox_event_event_type_created_at",
                "idx_outbox_event_created_at"
        );
    }

    @Test
    void savesProcessedEventAndRejectsDuplicateEventId() {
        UUID eventId = UUID.randomUUID();
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-07-08T12:00:00Z");

        processedEventRepository.saveAndFlush(ProcessedEventEntity.builder()
                .eventId(eventId)
                .eventType("CheckoutConfirmed")
                .occurredAt(occurredAt)
                .build());

        entityManager.clear();

        ProcessedEventEntity saved = processedEventRepository.findById(eventId).orElseThrow();
        assertThat(saved.getEventType()).isEqualTo("CheckoutConfirmed");
        assertThat(saved.getProcessedAt()).isNotNull();
        assertThat(saved.getOccurredAt()).isEqualTo(occurredAt);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into processed_events (event_id, event_type, occurred_at)
                        values (?, ?, ?)
                        """,
                eventId,
                "CheckoutConfirmed",
                occurredAt
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void processedEventRequiresEventType() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into processed_events (event_id, event_type, occurred_at)
                        values (?, ?, ?)
                        """,
                UUID.randomUUID(),
                null,
                OffsetDateTime.parse("2026-07-08T12:00:00Z")
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void processedEventMigrationCreatesExpectedIndexes() {
        List<String> indexNames = jdbcTemplate.queryForList(
                "select indexname from pg_indexes where schemaname = 'public' and tablename = 'processed_events'",
                String.class
        );

        assertThat(indexNames).contains(
                "processed_events_pkey",
                "idx_processed_events_event_type_processed_at"
        );
    }

    @Test
    void deletingOrderCascadesToItemsAndStatusHistory() {
        OrderEntity order = orderRepository.saveAndFlush(newOrder(
                UUID.randomUUID(),
                "customer-4",
                "store-4",
                OrderStatus.PENDING,
                OffsetDateTime.parse("2026-07-04T10:00:00Z")
        ));
        orderItemRepository.save(OrderItemEntity.builder()
                .orderId(order.getId())
                .lineNumber(1)
                .productId("product-3")
                .productName("Bread")
                .quantity(new BigDecimal("1.000"))
                .unit(ProductUnit.PCS)
                .unitPriceAmount(new BigDecimal("5000.00"))
                .grossAmount(new BigDecimal("5000.00"))
                .discountAmount(BigDecimal.ZERO)
                .netAmount(new BigDecimal("5000.00"))
                .createdAt(OffsetDateTime.parse("2026-07-04T10:01:00Z"))
                .build());
        orderStatusHistoryRepository.saveAndFlush(OrderStatusHistoryEntity.builder()
                .orderId(order.getId())
                .toStatus(OrderStatus.PENDING)
                .changedByType("SYSTEM")
                .createdAt(OffsetDateTime.parse("2026-07-04T10:00:30Z"))
                .build());

        orderRepository.deleteById(order.getId());
        orderRepository.flush();
        entityManager.clear();

        assertThat(orderItemRepository.findByOrderIdOrderByLineNumberAsc(order.getId())).isEmpty();
        assertThat(orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(order.getId())).isEmpty();
    }

    private OrderEntity newOrder(
            UUID orderId,
            String customerId,
            String storeId,
            OrderStatus status,
            OffsetDateTime createdAt
    ) {
        return OrderEntity.builder()
                .id(orderId)
                .customerId(customerId)
                .storeId(storeId)
                .cartId("cart-" + orderId)
                .status(status)
                .scheduleType(ScheduleType.ASAP)
                .subtotalAmount(new BigDecimal("33000.00"))
                .itemDiscountAmount(new BigDecimal("2000.00"))
                .orderDiscountAmount(new BigDecimal("3000.00"))
                .deliveryFeeAmount(new BigDecimal("8000.00"))
                .serviceFeeAmount(BigDecimal.ZERO)
                .smallOrderFeeAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("36000.00"))
                .currency("UZS")
                .deliveryAddressJson("{\"street\":\"Amir Temur 25\",\"city\":\"Tashkent\"}")
                .deliveryLat(new BigDecimal("41.311100"))
                .deliveryLng(new BigDecimal("69.279700"))
                .storeLat(new BigDecimal("41.320100"))
                .storeLng(new BigDecimal("69.240500"))
                .selectedPromoCodes(List.of())
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private OrderItemEntity newItem(UUID orderId, int lineNumber, String productId) {
        return OrderItemEntity.builder()
                .orderId(orderId)
                .lineNumber(lineNumber)
                .productId(productId)
                .productName("Apples")
                .quantity(new BigDecimal("2.000"))
                .unit(ProductUnit.KG)
                .unitPriceAmount(new BigDecimal("12000.00"))
                .grossAmount(new BigDecimal("24000.00"))
                .discountAmount(BigDecimal.ZERO)
                .netAmount(new BigDecimal("24000.00"))
                .createdAt(OffsetDateTime.parse("2026-07-04T10:01:00Z"))
                .build();
    }
}
