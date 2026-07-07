package com.sebet.order_service.customer.service;

import com.sebet.order_service.cache.repository.ActiveOrdersRedisRepository;
import com.sebet.order_service.cache.repository.OrderRedisRepository;
import com.sebet.order_service.cache.repository.OrderStatusRedisRepository;
import com.sebet.order_service.customer.dto.response.ActiveOrderDetailResponse;
import com.sebet.order_service.customer.dto.response.ActiveOrderItemResponse;
import com.sebet.order_service.customer.dto.response.CancelledOrderDetailResponse;
import com.sebet.order_service.customer.dto.response.OrderHistoryItemResponse;
import com.sebet.order_service.customer.dto.response.OrderStatusResponse;
import com.sebet.order_service.customer.dto.response.ScheduledOrderDetailResponse;
import com.sebet.order_service.order.command.CreateOrderCommand;
import com.sebet.order_service.order.command.CreateOrderItemCommand;
import com.sebet.order_service.order.command.CreateOrderResult;
import com.sebet.order_service.order.service.OrderCreationService;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.repository.OrderItemRepository;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderCancelledBy;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ProductUnit;
import com.sebet.order_service.shared.enums.ScheduleType;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CustomerOrderQueryServiceIntegrationTest {

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
    private CustomerOrderQueryService queryService;

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
    void getActiveOrders_returnsOrderThatWasJustCreated() {
        CreateOrderResult created = orderCreationService.createOrder(immediateCommand("cart-1", "customer-1"));
        String orderId = created.order().getId().toString();

        List<ActiveOrderItemResponse> result = queryService.getActiveOrders("customer-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).orderId()).isEqualTo(orderId);
        assertThat(result.get(0).storeId()).isEqualTo("store-1");
        assertThat(result.get(0).itemCount()).isEqualTo(2);
        assertThat(result.get(0).itemThumbnails()).contains("https://cdn.sebet.test/products/apple.png");
    }

    @Test
    void getActiveOrders_returnsEmptyListWhenUserHasNoActiveOrders() {
        List<ActiveOrderItemResponse> result = queryService.getActiveOrders("customer-nobody");

        assertThat(result).isEmpty();
    }

    @Test
    void getActiveOrderDetail_returnsSnapshotWithFullFourStepTimeline() {
        CreateOrderResult created = orderCreationService.createOrder(immediateCommand("cart-2", "customer-1"));
        String orderId = created.order().getId().toString();

        ActiveOrderDetailResponse result = queryService.getActiveOrderDetail("customer-1", orderId);

        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(result.items()).hasSize(2);
        assertThat(result.timeline()).hasSize(4);
        assertThat(result.timeline().get(0).status()).isEqualTo("PLACED");
        assertThat(result.timeline().get(0).occurredAt()).isNotNull();
        assertThat(result.timeline().get(1).occurredAt()).isNull();
        assertThat(result.verificationCode()).isNull();
    }

    @Test
    void getActiveOrderDetail_throwsNotFoundForWrongUser() {
        CreateOrderResult created = orderCreationService.createOrder(immediateCommand("cart-3", "customer-1"));
        String orderId = created.order().getId().toString();

        assertThatThrownBy(() -> queryService.getActiveOrderDetail("customer-other", orderId))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void routeOrderDetail_pendingOrderRedirectsToActive() {
        CreateOrderResult created = orderCreationService.createOrder(immediateCommand("cart-4", "customer-1"));
        String orderId = created.order().getId().toString();

        CustomerOrderRouterResult result = queryService.routeOrderDetail("customer-1", orderId);

        assertThat(result).isInstanceOf(CustomerOrderRouterResult.Redirect.class);
        assertThat(((CustomerOrderRouterResult.Redirect) result).location())
                .isEqualTo("/api/v1/orders/active/" + orderId);
    }

    @Test
    void routeOrderDetail_deliveredOrderReturnsDeliveredResultWithTimeline() {
        CreateOrderResult created = orderCreationService.createOrder(immediateCommand("cart-5", "customer-1"));
        OrderEntity order = orderRepository.findById(created.order().getId()).orElseThrow();
        OffsetDateTime base = order.getCreatedAt();

        orderStatusHistoryRepository.save(historyEntry(order.getId(), OrderStatus.PENDING, OrderStatus.CONFIRMED, base.plusMinutes(5)));
        orderStatusHistoryRepository.save(historyEntry(order.getId(), OrderStatus.CONFIRMED, OrderStatus.OUT_FOR_DELIVERY, base.plusMinutes(15)));
        orderStatusHistoryRepository.saveAndFlush(historyEntry(order.getId(), OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED, base.plusMinutes(30)));

        order.setStatus(OrderStatus.DELIVERED);
        order.setDeliveredAt(base.plusMinutes(30));
        orderRepository.saveAndFlush(order);

        CustomerOrderRouterResult result = queryService.routeOrderDetail("customer-1", order.getId().toString());

        assertThat(result).isInstanceOf(CustomerOrderRouterResult.Delivered.class);
        CustomerOrderRouterResult.Delivered delivered = (CustomerOrderRouterResult.Delivered) result;
        assertThat(delivered.response().orderId()).isEqualTo(order.getId().toString());
        assertThat(delivered.response().deliveredAt()).isNotNull();
        assertThat(delivered.response().timeline()).hasSize(4);
        assertThat(delivered.response().timeline().get(0).status()).isEqualTo("PLACED");
        assertThat(delivered.response().timeline().get(3).status()).isEqualTo("ARRIVED");
        assertThat(delivered.response().timeline().get(3).occurredAt()).isNotNull();
    }

    @Test
    void routeOrderDetail_cancelledOrderRedirectsToCancelled() {
        CreateOrderResult created = orderCreationService.createOrder(immediateCommand("cart-6", "customer-1"));
        OrderEntity order = orderRepository.findById(created.order().getId()).orElseThrow();
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledBy(OrderCancelledBy.USER);
        order.setCancelledAt(OffsetDateTime.now());
        orderRepository.saveAndFlush(order);

        CustomerOrderRouterResult result = queryService.routeOrderDetail("customer-1", order.getId().toString());

        assertThat(result).isInstanceOf(CustomerOrderRouterResult.Redirect.class);
        assertThat(((CustomerOrderRouterResult.Redirect) result).location())
                .isEqualTo("/api/v1/orders/cancelled/" + order.getId());
    }

    @Test
    void getOrderStatus_returnsStatusFromC4() {
        CreateOrderResult created = orderCreationService.createOrder(immediateCommand("cart-7", "customer-1"));
        String orderId = created.order().getId().toString();

        OrderStatusResponse result = queryService.getOrderStatus("customer-1", orderId);

        assertThat(result.status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void getOrderStatus_fallsBackToDbWhenC4Expired() {
        CreateOrderResult created = orderCreationService.createOrder(immediateCommand("cart-8", "customer-1"));
        String orderId = created.order().getId().toString();

        orderStatusRedisRepository.delete(orderId);
        orderRedisRepository.delete(orderId);

        OrderStatusResponse result = queryService.getOrderStatus("customer-1", orderId);

        assertThat(result.status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void getCancelledOrderDetail_returnsDetailAfterCancellation() {
        CreateOrderResult created = orderCreationService.createOrder(immediateCommand("cart-9", "customer-1"));
        OrderEntity order = orderRepository.findById(created.order().getId()).orElseThrow();
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledBy(OrderCancelledBy.USER);
        order.setCancelledAt(OffsetDateTime.now());
        orderRepository.saveAndFlush(order);

        CancelledOrderDetailResponse result =
                queryService.getCancelledOrderDetail("customer-1", order.getId().toString());

        assertThat(result.orderId()).isEqualTo(order.getId().toString());
        assertThat(result.cancelledBy()).isEqualTo(OrderCancelledBy.USER);
        assertThat(result.items()).hasSize(2);
        assertThat(result.refund()).isNotNull();
    }

    @Test
    void getScheduledOrderDetail_returnsDetailWithCanCancelTrue() {
        OffsetDateTime scheduledFor = OffsetDateTime.now().plusHours(4);
        CreateOrderResult created = orderCreationService.createOrder(
                scheduledCommand("cart-10", "customer-1", scheduledFor));

        ScheduledOrderDetailResponse result =
                queryService.getScheduledOrderDetail("customer-1", created.order().getId().toString());

        assertThat(result.scheduledFor()).isNotNull();
        assertThat(result.canCancel()).isTrue();
        assertThat(result.items()).hasSize(2);
    }

    @Test
    void getOrderHistory_returnsPaginatedHistoryForDeliveredAndCancelled() {
        CreateOrderResult r1 = orderCreationService.createOrder(immediateCommand("cart-h1", "customer-1"));
        CreateOrderResult r2 = orderCreationService.createOrder(immediateCommand("cart-h2", "customer-1"));

        OrderEntity o1 = orderRepository.findById(r1.order().getId()).orElseThrow();
        o1.setStatus(OrderStatus.DELIVERED);
        o1.setDeliveredAt(OffsetDateTime.now());
        orderRepository.save(o1);

        OrderEntity o2 = orderRepository.findById(r2.order().getId()).orElseThrow();
        o2.setStatus(OrderStatus.CANCELLED);
        o2.setCancelledBy(OrderCancelledBy.SYSTEM);
        o2.setCancelledAt(OffsetDateTime.now());
        orderRepository.saveAndFlush(o2);

        PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<OrderHistoryItemResponse> result = queryService.getOrderHistory("customer-1", pageable);

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent())
                .extracting(OrderHistoryItemResponse::route)
                .containsExactlyInAnyOrder(
                        OrderHistoryItemResponse.OrderDetailRoute.DELIVERED,
                        OrderHistoryItemResponse.OrderDetailRoute.CANCELLED
                );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CreateOrderCommand immediateCommand(String cartId, String customerId) {
        return new CreateOrderCommand(
                cartId,
                customerId,
                "store-1",
                ScheduleType.IMMEDIATE,
                null,
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
                                "product-1", "Apples",
                                new BigDecimal("2.000"), ProductUnit.KG,
                                new BigDecimal("12000.00"),
                                new BigDecimal("24000.00"),
                                new BigDecimal("2000.00"),
                                new BigDecimal("22000.00"),
                                "https://cdn.sebet.test/products/apple.png"
                        ),
                        new CreateOrderItemCommand(
                                "product-2", "Milk",
                                new BigDecimal("1.000"), ProductUnit.PCS,
                                new BigDecimal("9000.00"),
                                new BigDecimal("9000.00"),
                                BigDecimal.ZERO,
                                new BigDecimal("9000.00"),
                                null
                        )
                )
        );
    }

    private CreateOrderCommand scheduledCommand(String cartId, String customerId, OffsetDateTime scheduledFor) {
        return new CreateOrderCommand(
                cartId,
                customerId,
                "store-1",
                ScheduleType.SCHEDULED,
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
                                "product-1", "Apples",
                                new BigDecimal("2.000"), ProductUnit.KG,
                                new BigDecimal("12000.00"),
                                new BigDecimal("24000.00"),
                                new BigDecimal("2000.00"),
                                new BigDecimal("22000.00"),
                                "https://cdn.sebet.test/products/apple.png"
                        ),
                        new CreateOrderItemCommand(
                                "product-2", "Milk",
                                new BigDecimal("1.000"), ProductUnit.PCS,
                                new BigDecimal("9000.00"),
                                new BigDecimal("9000.00"),
                                BigDecimal.ZERO,
                                new BigDecimal("9000.00"),
                                null
                        )
                )
        );
    }

    private OrderStatusHistoryEntity historyEntry(
            java.util.UUID orderId, OrderStatus from, OrderStatus to, OffsetDateTime at) {
        OrderStatusHistoryEntity h = new OrderStatusHistoryEntity();
        h.setOrderId(orderId);
        h.setFromStatus(from);
        h.setToStatus(to);
        h.setChangedByType("SYSTEM");
        h.setCreatedAt(at);
        return h;
    }
}
