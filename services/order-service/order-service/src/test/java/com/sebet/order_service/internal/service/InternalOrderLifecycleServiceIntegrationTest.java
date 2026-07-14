package com.sebet.order_service.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.keys.RedisKeys;
import com.sebet.order_service.internal.dto.request.UpdateAfterProposalItemRequest;
import com.sebet.order_service.internal.dto.request.UpdateAfterProposalRequest;
import com.sebet.order_service.internal.dto.response.UpdateAfterProposalResponse;
import com.sebet.order_service.persistence.entity.IdempotentCommandEntity;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderItemEntity;
import com.sebet.order_service.persistence.entity.OrderProposalEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.entity.OutboxEventEntity;
import com.sebet.order_service.persistence.repository.IdempotentCommandRepository;
import com.sebet.order_service.persistence.repository.OrderItemRepository;
import com.sebet.order_service.persistence.repository.OrderProposalRepository;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.persistence.repository.OutboxEventRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ProductUnit;
import com.sebet.order_service.shared.enums.ProposalStatus;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class InternalOrderLifecycleServiceIntegrationTest {

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
        registry.add("order-service.internal.secret", () -> "test-internal-secret");
    }

    @Autowired
    private InternalOrderLifecycleService service;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private OrderProposalRepository orderProposalRepository;

    @Autowired
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private IdempotentCommandRepository idempotentCommandRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearState() {
        outboxEventRepository.deleteAll();
        idempotentCommandRepository.deleteAll();
        orderStatusHistoryRepository.deleteAll();
        orderProposalRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.flushDb();
            return null;
        });
    }

    @Test
    void updateAfterProposalPersistsAtomicDbChangesAndRemovesProposalTimelineMarker() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        OrderEntity order = order(orderId);
        orderRepository.saveAndFlush(order);
        orderItemRepository.saveAllAndFlush(List.of(
                item(orderId, 1, "apple", "Apple", new BigDecimal("2.000"), new BigDecimal("20000.00")),
                item(orderId, 2, "milk", "Milk", new BigDecimal("1.000"), new BigDecimal("8000.00"))
        ));
        orderProposalRepository.saveAndFlush(proposal(proposalId, orderId));

        String orderIdText = orderId.toString();
        stringRedisTemplate.opsForValue().set(RedisKeys.order(orderIdText), "{\"stale\":true}");
        stringRedisTemplate.opsForValue().set(RedisKeys.orderProposals(orderIdText), "{\"proposal\":true}");
        stringRedisTemplate.opsForList().rightPush(
                RedisKeys.orderTimeline(orderIdText),
                "{\"status\":\"PLACED\",\"occurredAt\":\"2026-07-10T09:00:00Z\"}"
        );
        stringRedisTemplate.opsForList().rightPush(
                RedisKeys.orderTimeline(orderIdText),
                "{\"status\":\"AWAITING_CUSTOMER_RESPONSE\",\"occurredAt\":\"2026-07-10T09:05:00Z\"}"
        );

        UpdateAfterProposalRequest request = updateAfterProposalRequest(proposalId);

        UpdateAfterProposalResponse response = service.updateAfterProposal(orderIdText, request, "idem-apply-1");

        assertThat(response.orderId()).isEqualTo(orderIdText);
        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.proposalId()).isEqualTo(proposalId.toString());

        OrderEntity updatedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(updatedOrder.getSubtotalAmount()).isEqualByComparingTo("18000.00");
        assertThat(updatedOrder.getItemDiscountAmount()).isEqualByComparingTo("1000.00");
        assertThat(updatedOrder.getOrderDiscountAmount()).isEqualByComparingTo("500.00");
        assertThat(updatedOrder.getTotalAmount()).isEqualByComparingTo("19500.00");
        assertThat(updatedOrder.getSelectedPromoCodes()).containsExactly("PROMO500");

        assertThat(orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId))
                .extracting(
                        OrderItemEntity::getProductId,
                        OrderItemEntity::getQuantity,
                        OrderItemEntity::getGrossAmount,
                        OrderItemEntity::getDiscountAmount,
                        OrderItemEntity::getNetAmount
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "apple",
                                new BigDecimal("1.000"),
                                new BigDecimal("10000.00"),
                                new BigDecimal("1000.00"),
                                new BigDecimal("9000.00")
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "milk",
                                new BigDecimal("1.000"),
                                new BigDecimal("8000.00"),
                                BigDecimal.ZERO.setScale(2),
                                new BigDecimal("8000.00")
                        )
                );

        OrderProposalEntity appliedProposal = orderProposalRepository.findById(proposalId).orElseThrow();
        assertThat(appliedProposal.getStatus()).isEqualTo(ProposalStatus.APPLIED);
        assertThat(appliedProposal.getAppliedAt()).isNotNull();

        List<OrderStatusHistoryEntity> history = orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
        assertThat(history)
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getFromStatus()).isEqualTo(OrderStatus.AWAITING_CUSTOMER_RESPONSE);
                    assertThat(entry.getToStatus()).isEqualTo(OrderStatus.CONFIRMED);
                    assertThat(entry.getReason()).isEqualTo("PROPOSAL_APPLIED");
                    assertThat(entry.getMetadataJson()).contains("calc-1", proposalId.toString());
                });

        OutboxEventEntity event = outboxEventRepository.findAll().get(0);
        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(event.getEventType()).isEqualTo("OrderProposalApplied");
        assertThat(payload.path("eventType").asText()).isEqualTo("OrderProposalApplied");
        assertThat(payload.path("data").path("promoCalculationId").asText()).isEqualTo("calc-1");
        assertThat(payload.path("data").path("items")).hasSize(2);

        IdempotentCommandEntity idempotentCommand = idempotentCommandRepository
                .findByActionAndIdempotencyKey("INTERNAL_UPDATE_AFTER_PROPOSAL", "idem-apply-1")
                .orElseThrow();
        assertThat(idempotentCommand.getStatus()).isEqualTo("COMPLETED");
        assertThat(idempotentCommand.getResponseJson()).contains("\"status\": \"CONFIRMED\"");

        assertThat(stringRedisTemplate.hasKey(RedisKeys.order(orderIdText))).isFalse();
        assertThat(stringRedisTemplate.hasKey(RedisKeys.orderProposals(orderIdText))).isFalse();
        assertThat(stringRedisTemplate.opsForValue().get(RedisKeys.orderStatus(orderIdText)))
                .isEqualTo("CONFIRMED|customer-1|store-1");
        assertThat(stringRedisTemplate.opsForList().range(RedisKeys.orderTimeline(orderIdText), 0, -1))
                .containsExactly("{\"status\":\"PLACED\",\"occurredAt\":\"2026-07-10T09:00:00Z\"}");
    }

    private OrderEntity order(UUID id) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00Z");
        return OrderEntity.builder()
                .id(id)
                .customerId("customer-1")
                .storeId("store-1")
                .cartId("cart-" + id)
                .status(OrderStatus.AWAITING_CUSTOMER_RESPONSE)
                .scheduleType(ScheduleType.ASAP)
                .subtotalAmount(new BigDecimal("28000.00"))
                .itemDiscountAmount(BigDecimal.ZERO.setScale(2))
                .orderDiscountAmount(BigDecimal.ZERO.setScale(2))
                .deliveryFeeAmount(new BigDecimal("3000.00"))
                .serviceFeeAmount(BigDecimal.ZERO.setScale(2))
                .smallOrderFeeAmount(BigDecimal.ZERO.setScale(2))
                .totalAmount(new BigDecimal("31000.00"))
                .currency("UZS")
                .deliveryAddressJson("{\"street\":\"Amir Temur 25\"}")
                .deliveryLat(new BigDecimal("41.311100"))
                .deliveryLng(new BigDecimal("69.279700"))
                .storeLat(new BigDecimal("41.320100"))
                .storeLng(new BigDecimal("69.240500"))
                .selectedPromoCodes(List.of())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private OrderItemEntity item(
            UUID orderId,
            int lineNumber,
            String productId,
            String productName,
            BigDecimal quantity,
            BigDecimal grossAmount
    ) {
        return OrderItemEntity.builder()
                .orderId(orderId)
                .lineNumber(lineNumber)
                .productId(productId)
                .productName(productName)
                .quantity(quantity)
                .unit(ProductUnit.PCS)
                .unitPriceAmount(new BigDecimal("10000.00"))
                .grossAmount(grossAmount)
                .discountAmount(BigDecimal.ZERO.setScale(2))
                .netAmount(grossAmount)
                .sku("sku-" + productId)
                .imageUrl("https://cdn.sebet.test/" + productId + ".png")
                .build();
    }

    private OrderProposalEntity proposal(UUID proposalId, UUID orderId) {
        return OrderProposalEntity.builder()
                .id(proposalId)
                .orderId(orderId)
                .storeId("store-1")
                .proposedAt(OffsetDateTime.parse("2026-07-10T09:05:00Z"))
                .itemsJson("[{\"productId\":\"apple\"},{\"productId\":\"milk\"}]")
                .status(ProposalStatus.ACCEPTED)
                .globalDecision("ACCEPT_ALL")
                .respondedAt(OffsetDateTime.parse("2026-07-10T09:10:00Z"))
                .build();
    }

    private UpdateAfterProposalRequest updateAfterProposalRequest(UUID proposalId) {
        return new UpdateAfterProposalRequest(
                proposalId,
                "calc-1",
                "UZS",
                new BigDecimal("18000.00"),
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("3000.00"),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                new BigDecimal("19500.00"),
                List.of("PROMO500"),
                List.of(
                        new UpdateAfterProposalItemRequest(
                                "apple",
                                new BigDecimal("1.000"),
                                ProductUnit.PCS,
                                new BigDecimal("10000.00"),
                                new BigDecimal("10000.00"),
                                new BigDecimal("1000.00"),
                                new BigDecimal("9000.00")
                        ),
                        new UpdateAfterProposalItemRequest(
                                "milk",
                                new BigDecimal("1.000"),
                                ProductUnit.PCS,
                                new BigDecimal("8000.00"),
                                new BigDecimal("8000.00"),
                                BigDecimal.ZERO.setScale(2),
                                new BigDecimal("8000.00")
                        )
                )
        );
    }
}
