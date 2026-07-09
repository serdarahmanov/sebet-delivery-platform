package com.sebet.order_service.driver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.DriverInfo;
import com.sebet.order_service.cache.dto.RedisOrder;
import com.sebet.order_service.cache.dto.DeliveryAddress;
import com.sebet.order_service.cache.dto.StoreLocation;
import com.sebet.order_service.cache.dto.VerificationCodeCacheDto;
import com.sebet.order_service.cache.repository.OrderRedisRepository;
import com.sebet.order_service.cache.repository.OrderStatusRedisRepository;
import com.sebet.order_service.cache.repository.VerificationCodeRedisRepository;
import com.sebet.order_service.cache.service.OrderCacheEvictionService;
import com.sebet.order_service.driver.dto.response.DriverArriveResponse;
import com.sebet.order_service.driver.dto.response.DriverCompleteDeliveryResponse;
import com.sebet.order_service.driver.dto.response.DriverDeclineResponse;
import com.sebet.order_service.driver.dto.response.DriverOrderDetailResponse;
import com.sebet.order_service.driver.dto.response.DriverPickupResponse;
import com.sebet.order_service.order.event.OrderEventOutboxWriter;
import com.sebet.order_service.order.service.OrderLifecycleResult;
import com.sebet.order_service.order.service.OrderLifecycleService;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderItemEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.repository.OrderItemRepository;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ProductUnit;
import com.sebet.order_service.shared.enums.ScheduleType;
import com.sebet.order_service.shared.exception.CacheInvalidationFailedException;
import com.sebet.order_service.shared.exception.DriverNotAssignedException;
import com.sebet.order_service.shared.exception.OrderInvalidTransitionException;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
import com.sebet.order_service.shared.exception.VerificationCodeNotFoundException;
import com.sebet.order_service.shared.idempotency.IdempotentCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriverOrderLifecycleServiceTest {

    private final OrderLifecycleService orderLifecycleService = mock(OrderLifecycleService.class);
    private final OrderEventOutboxWriter orderEventOutboxWriter = mock(OrderEventOutboxWriter.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
    private final OrderRedisRepository orderRedisRepository = mock(OrderRedisRepository.class);
    private final OrderStatusRedisRepository orderStatusRedisRepository = mock(OrderStatusRedisRepository.class);
    private final VerificationCodeRedisRepository verificationCodeRedisRepository = mock(VerificationCodeRedisRepository.class);
    private final OrderStatusHistoryRepository orderStatusHistoryRepository = mock(OrderStatusHistoryRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IdempotentCommandService idempotentCommandService = mock(IdempotentCommandService.class);
    private final OrderCacheEvictionService orderCacheEvictionService = mock(OrderCacheEvictionService.class);

    private final DriverOrderLifecycleService service = new DriverOrderLifecycleService(
            orderLifecycleService,
            orderEventOutboxWriter,
            orderRepository,
            orderItemRepository,
            orderRedisRepository,
            orderStatusRedisRepository,
            verificationCodeRedisRepository,
            orderStatusHistoryRepository,
            objectMapper,
            idempotentCommandService,
            orderCacheEvictionService
    );

    @BeforeEach
    void runIdempotentOperation() {
        when(idempotentCommandService.execute(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
    }

    // ── confirmPickup ────────────────────────────────────────────────────────

    @Test
    void getOrderDetail_returnsAssignedOrderDetailFromDatabase() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.READY_FOR_PICKUP, "driver-1");
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(id))
                .thenReturn(List.of(item(id, 1, "product-1", "Burger")));
        when(orderRedisRepository.findById(id.toString())).thenReturn(Optional.empty());

        DriverOrderDetailResponse response = service.getOrderDetail("driver-1", id.toString());

        assertThat(response.orderId()).isEqualTo(id.toString());
        assertThat(response.orderNumber()).startsWith("#");
        assertThat(response.status()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
        assertThat(response.pickup().storeId()).isEqualTo("store-1");
        assertThat(response.pickup().storeName()).isEqualTo("store-1");
        assertThat(response.pickup().lat()).isEqualTo(41.320000);
        assertThat(response.pickup().lng()).isEqualTo(69.240000);
        assertThat(response.dropoff().street()).isEqualTo("Amir Temur 25");
        assertThat(response.dropoff().city()).isEqualTo("Tashkent");
        assertThat(response.dropoff().apartment()).isEqualTo("12");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).productId()).isEqualTo("product-1");
        assertThat(response.items().get(0).name()).isEqualTo("Burger");
        assertThat(response.createdAt()).isEqualTo(order.getCreatedAt().toString());
    }

    @Test
    void getOrderDetail_prefersCachedStoreNameItemsAndEstimatedDelivery() {
        UUID id = UUID.randomUUID();
        RedisOrder cachedOrder = RedisOrder.builder()
                .orderId(id.toString())
                .storeId("store-1")
                .storeName("Sebet Kitchen")
                .driver(DriverInfo.builder().driverId("driver-1").build())
                .storeLocation(StoreLocation.builder()
                        .lat(41.330000)
                        .lng(69.250000)
                        .build())
                .deliveryAddress(DeliveryAddress.builder()
                        .street("Cached Street 9")
                        .city("Tashkent")
                        .lat(41.310000)
                        .lng(69.270000)
                        .build())
                .items(List.of(com.sebet.order_service.cache.dto.OrderItem.builder()
                        .productId("cached-product")
                        .name("Cached Item")
                        .quantity(new BigDecimal("2.000"))
                        .unitPrice(new BigDecimal("12000.00"))
                        .build()))
                .estimatedDeliveryAt("2026-07-09T12:30:00Z")
                .createdAt("2026-07-09T11:00:00Z")
                .build();
        when(orderRedisRepository.findById(id.toString())).thenReturn(Optional.of(cachedOrder));
        when(orderStatusRedisRepository.findById(id.toString()))
                .thenReturn(Optional.of(new OrderStatusRedisRepository.Entry(
                        "CONFIRMED",
                        "customer-1",
                        "store-1"
                )));

        DriverOrderDetailResponse response = service.getOrderDetail("driver-1", id.toString());

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.pickup().storeName()).isEqualTo("Sebet Kitchen");
        assertThat(response.pickup().lat()).isEqualTo(41.330000);
        assertThat(response.dropoff().street()).isEqualTo("Cached Street 9");
        assertThat(response.items()).singleElement()
                .extracting(DriverOrderDetailResponse.ItemLine::productId)
                .isEqualTo("cached-product");
        assertThat(response.estimatedDeliveryAt()).isEqualTo("2026-07-09T12:30:00Z");
        assertThat(response.createdAt()).isEqualTo("2026-07-09T11:00:00Z");
        verify(orderRepository, never()).findById(any());
        verify(orderItemRepository, never()).findByOrderIdOrderByLineNumberAsc(any());
    }

    @Test
    void getOrderDetail_throwsDriverNotAssignedWhenDriverDoesNotMatch() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id))
                .thenReturn(Optional.of(order(id, OrderStatus.CONFIRMED, "other-driver")));

        assertThatThrownBy(() -> service.getOrderDetail("driver-1", id.toString()))
                .isInstanceOf(DriverNotAssignedException.class);

        verify(orderItemRepository, never()).findByOrderIdOrderByLineNumberAsc(any());
    }

    @Test
    void getOrderDetail_fallsBackToDatabaseWhenCachedDriverIsStale() {
        UUID id = UUID.randomUUID();
        RedisOrder cachedOrder = RedisOrder.builder()
                .orderId(id.toString())
                .driver(DriverInfo.builder().driverId("old-driver").build())
                .build();
        OrderEntity order = order(id, OrderStatus.READY_FOR_PICKUP, "driver-1");
        when(orderRedisRepository.findById(id.toString())).thenReturn(Optional.of(cachedOrder));
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(id))
                .thenReturn(List.of(item(id, 1, "product-1", "Burger")));

        DriverOrderDetailResponse response = service.getOrderDetail("driver-1", id.toString());

        assertThat(response.status()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
        assertThat(response.items()).singleElement()
                .extracting(DriverOrderDetailResponse.ItemLine::productId)
                .isEqualTo("product-1");
    }

    @Test
    void getOrderDetail_throwsDriverNotAssignedAfterDatabaseFallbackWhenCachedDriverAndDbDoNotMatch() {
        UUID id = UUID.randomUUID();
        RedisOrder cachedOrder = RedisOrder.builder()
                .orderId(id.toString())
                .driver(DriverInfo.builder().driverId("old-driver").build())
                .build();
        when(orderRedisRepository.findById(id.toString())).thenReturn(Optional.of(cachedOrder));
        when(orderRepository.findById(id))
                .thenReturn(Optional.of(order(id, OrderStatus.CONFIRMED, "other-driver")));

        assertThatThrownBy(() -> service.getOrderDetail("driver-1", id.toString()))
                .isInstanceOf(DriverNotAssignedException.class);

        verify(orderItemRepository, never()).findByOrderIdOrderByLineNumberAsc(any());
    }

    @Test
    void getOrderDetail_throwsNotFoundForInvalidOrderId() {
        assertThatThrownBy(() -> service.getOrderDetail("driver-1", "not-a-uuid"))
                .isInstanceOf(OrderNotFoundException.class);

        verify(orderRepository, never()).findById(any());
    }

    @Test
    void confirmPickup_returnsOutForDeliveryStatus() {
        String orderId = UUID.randomUUID().toString();
        when(orderLifecycleService.driverPickup(orderId, "driver-1"))
                .thenReturn(lifecycleResult(OrderStatus.READY_FOR_PICKUP, OrderStatus.OUT_FOR_DELIVERY));

        DriverPickupResponse response = service.confirmPickup("driver-1", orderId);

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.status()).isEqualTo("OUT_FOR_DELIVERY");
    }

    @Test
    void confirmPickup_delegatesToLifecycleServiceWithCorrectArguments() {
        String orderId = UUID.randomUUID().toString();
        when(orderLifecycleService.driverPickup(orderId, "driver-1"))
                .thenReturn(lifecycleResult(OrderStatus.READY_FOR_PICKUP, OrderStatus.OUT_FOR_DELIVERY));

        service.confirmPickup("driver-1", orderId);

        verify(orderLifecycleService).driverPickup(orderId, "driver-1");
    }

    // ── markArrived ──────────────────────────────────────────────────────────

    @Test
    void markArrived_returnsArrivedStatus() {
        String orderId = UUID.randomUUID().toString();
        when(orderLifecycleService.driverArrive(eq(orderId), eq("driver-1"), any()))
                .thenReturn(lifecycleResult(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.ARRIVED));

        DriverArriveResponse response = service.markArrived("driver-1", orderId);

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.status()).isEqualTo("ARRIVED");
    }

    @Test
    void markArrived_writesVerificationCodeToRedis() {
        String orderId = UUID.randomUUID().toString();
        when(orderLifecycleService.driverArrive(eq(orderId), eq("driver-1"), any()))
                .thenReturn(lifecycleResult(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.ARRIVED));

        service.markArrived("driver-1", orderId);

        ArgumentCaptor<VerificationCodeCacheDto> captor = ArgumentCaptor.forClass(VerificationCodeCacheDto.class);
        verify(verificationCodeRedisRepository).save(eq(orderId), captor.capture());
        VerificationCodeCacheDto saved = captor.getValue();
        assertThat(saved.getCode()).matches("\\d{2}");
        assertThat(saved.getGeneratedAt()).isNotNull();
        assertThat(saved.getVerifiedAt()).isNull();
    }

    @Test
    void markArrived_codesPassedToRedisAndMetadataAreTheSame() {
        String orderId = UUID.randomUUID().toString();
        when(orderLifecycleService.driverArrive(eq(orderId), eq("driver-1"), any()))
                .thenReturn(lifecycleResult(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.ARRIVED));

        service.markArrived("driver-1", orderId);

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(orderLifecycleService).driverArrive(eq(orderId), eq("driver-1"), metadataCaptor.capture());

        ArgumentCaptor<VerificationCodeCacheDto> redisCaptor = ArgumentCaptor.forClass(VerificationCodeCacheDto.class);
        verify(verificationCodeRedisRepository).save(eq(orderId), redisCaptor.capture());

        String metadataJson = metadataCaptor.getValue();
        String redisCode = redisCaptor.getValue().getCode();
        assertThat(metadataJson).contains("\"code\":\"" + redisCode + "\"");
    }

    // ── completeDelivery — happy paths ───────────────────────────────────────

    @Test
    void completeDelivery_succeedsWhenCodeMatchesRedisCache() {
        String orderId = UUID.randomUUID().toString();
        OffsetDateTime deliveredAt = OffsetDateTime.parse("2026-07-07T12:00:00Z");
        when(verificationCodeRedisRepository.findById(orderId))
                .thenReturn(Optional.of(verificationCode("42")));
        when(orderLifecycleService.driverComplete(orderId, "driver-1"))
                .thenReturn(lifecycleResult(OrderStatus.ARRIVED, OrderStatus.DELIVERED, deliveredAt));

        DriverCompleteDeliveryResponse response = service.completeDelivery("driver-1", orderId, "42");

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.status()).isEqualTo("DELIVERED");
        assertThat(response.deliveredAt()).isEqualTo(deliveredAt.toString());
        verify(orderStatusHistoryRepository, never()).findFirstByOrderIdAndToStatus(any(), any());
    }

    @Test
    void completeDelivery_succeedsWhenCodeMatchesDbFallback() {
        UUID id = UUID.randomUUID();
        String orderId = id.toString();
        when(verificationCodeRedisRepository.findById(orderId)).thenReturn(Optional.empty());
        when(orderStatusHistoryRepository.findFirstByOrderIdAndToStatus(id, OrderStatus.ARRIVED))
                .thenReturn(Optional.of(historyWithCode("07")));
        when(orderLifecycleService.driverComplete(orderId, "driver-1"))
                .thenReturn(lifecycleResult(OrderStatus.ARRIVED, OrderStatus.DELIVERED));

        DriverCompleteDeliveryResponse response = service.completeDelivery("driver-1", orderId, "07");

        assertThat(response.status()).isEqualTo("DELIVERED");
        verify(orderLifecycleService).driverComplete(orderId, "driver-1");
    }

    // ── completeDelivery — wrong code ────────────────────────────────────────

    @Test
    void completeDelivery_throwsWhenCodeDoesNotMatchCache() {
        String orderId = UUID.randomUUID().toString();
        when(verificationCodeRedisRepository.findById(orderId))
                .thenReturn(Optional.of(verificationCode("42")));

        assertThatThrownBy(() -> service.completeDelivery("driver-1", orderId, "99"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");

        verify(orderLifecycleService, never()).driverComplete(any(), any());
    }

    @Test
    void completeDelivery_throwsWhenCodeDoesNotMatchDbFallback() {
        UUID id = UUID.randomUUID();
        String orderId = id.toString();
        when(verificationCodeRedisRepository.findById(orderId)).thenReturn(Optional.empty());
        when(orderStatusHistoryRepository.findFirstByOrderIdAndToStatus(id, OrderStatus.ARRIVED))
                .thenReturn(Optional.of(historyWithCode("07")));

        assertThatThrownBy(() -> service.completeDelivery("driver-1", orderId, "99"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");

        verify(orderLifecycleService, never()).driverComplete(any(), any());
    }

    // ── completeDelivery — code not found ────────────────────────────────────

    @Test
    void completeDelivery_throwsVerificationCodeNotFoundWhenNeitherCacheNorDbHasRecord() {
        UUID id = UUID.randomUUID();
        String orderId = id.toString();
        when(verificationCodeRedisRepository.findById(orderId)).thenReturn(Optional.empty());
        when(orderStatusHistoryRepository.findFirstByOrderIdAndToStatus(id, OrderStatus.ARRIVED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeDelivery("driver-1", orderId, "42"))
                .isInstanceOf(VerificationCodeNotFoundException.class)
                .hasMessageContaining(orderId);

        verify(orderLifecycleService, never()).driverComplete(any(), any());
    }

    @Test
    void completeDelivery_throwsVerificationCodeNotFoundWhenDbMetadataIsNull() {
        UUID id = UUID.randomUUID();
        String orderId = id.toString();
        when(verificationCodeRedisRepository.findById(orderId)).thenReturn(Optional.empty());
        when(orderStatusHistoryRepository.findFirstByOrderIdAndToStatus(id, OrderStatus.ARRIVED))
                .thenReturn(Optional.of(historyWithNullMetadata()));

        assertThatThrownBy(() -> service.completeDelivery("driver-1", orderId, "42"))
                .isInstanceOf(VerificationCodeNotFoundException.class);

        verify(orderLifecycleService, never()).driverComplete(any(), any());
    }

    @Test
    void completeDelivery_throwsVerificationCodeNotFoundWhenDbMetadataHasNoCodeField() {
        UUID id = UUID.randomUUID();
        String orderId = id.toString();
        when(verificationCodeRedisRepository.findById(orderId)).thenReturn(Optional.empty());
        when(orderStatusHistoryRepository.findFirstByOrderIdAndToStatus(id, OrderStatus.ARRIVED))
                .thenReturn(Optional.of(historyWithMetadata("{\"other\":\"value\"}")));

        assertThatThrownBy(() -> service.completeDelivery("driver-1", orderId, "42"))
                .isInstanceOf(VerificationCodeNotFoundException.class);

        verify(orderLifecycleService, never()).driverComplete(any(), any());
    }

    @Test
    void completeDelivery_throwsOrderNotFoundForInvalidUuidWhenCacheMisses() {
        when(verificationCodeRedisRepository.findById("not-a-uuid")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeDelivery("driver-1", "not-a-uuid", "42"))
                .isInstanceOf(OrderNotFoundException.class);

        verify(orderStatusHistoryRepository, never()).findFirstByOrderIdAndToStatus(any(), any());
        verify(orderLifecycleService, never()).driverComplete(any(), any());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @Test
    void declineAssignment_clearsDriverAssignmentAndKeepsStatus() {
        UUID id = UUID.randomUUID();
        OffsetDateTime assignedAt = OffsetDateTime.parse("2026-07-09T10:00:00Z");
        OrderEntity order = order(id, OrderStatus.READY_FOR_PICKUP, "driver-1");
        order.setDriverAssignedAt(assignedAt);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);
        when(orderRedisRepository.findById(id.toString())).thenReturn(Optional.empty());

        DriverDeclineResponse response = service.declineAssignment("driver-1", id.toString(), "idem-1");

        assertThat(response.orderId()).isEqualTo(id.toString());
        assertThat(response.status()).isEqualTo("READY_FOR_PICKUP");
        assertThat(order.getDriverId()).isNull();
        assertThat(order.getDriverAssignedAt()).isNull();
        verify(orderCacheEvictionService).evictC2OrRequestEviction(id.toString(), "DRIVER_DECLINE_ASSIGNMENT", "idem-1");
        verify(orderRepository).saveAndFlush(order);
        verify(orderEventOutboxWriter).saveDriverAssignmentDeclined(
                eq(order),
                eq("driver-1"),
                any(OffsetDateTime.class),
                eq("DRIVER_DECLINED")
        );
        verify(orderLifecycleService, never()).driverPickup(any(), any());
    }

    @Test
    void declineAssignment_evictsCachedOrderSnapshot() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.CONFIRMED, "driver-1");
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);

        service.declineAssignment("driver-1", id.toString(), "idem-1");

        verify(orderCacheEvictionService).evictC2OrRequestEviction(id.toString(), "DRIVER_DECLINE_ASSIGNMENT", "idem-1");
    }

    @Test
    void declineAssignment_throwsWhenCacheEvictionFallbackCannotBeRecorded() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.CONFIRMED, "driver-1");
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);
        CacheInvalidationFailedException failure =
                new CacheInvalidationFailedException(id.toString(), new IllegalStateException("outbox unavailable"));
        doThrow(failure)
                .when(orderCacheEvictionService)
                .evictC2OrRequestEviction(id.toString(), "DRIVER_DECLINE_ASSIGNMENT", "idem-1");

        assertThatThrownBy(() -> service.declineAssignment("driver-1", id.toString(), "idem-1"))
                .isInstanceOf(CacheInvalidationFailedException.class);

        assertThat(order.getDriverId()).isNull();
        verify(orderEventOutboxWriter).saveDriverAssignmentDeclined(
                eq(order),
                eq("driver-1"),
                any(OffsetDateTime.class),
                eq("DRIVER_DECLINED")
        );
    }

    @Test
    void declineAssignment_retriesCacheEvictionWhenIdempotencyRecordExists() {
        UUID id = UUID.randomUUID();
        DriverDeclineResponse stored = new DriverDeclineResponse(id.toString(), "CONFIRMED");
        when(idempotentCommandService.execute(anyString(), eq("idem-1"), eq(id.toString()), anyString(), any(), any()))
                .thenReturn(stored);

        DriverDeclineResponse response = service.declineAssignment("driver-1", id.toString(), "idem-1");

        assertThat(response).isEqualTo(stored);
        verify(orderRepository, never()).findById(any());
        verify(orderEventOutboxWriter, never()).saveDriverAssignmentDeclined(any(), any(), any(), any());
        verify(orderCacheEvictionService).evictC2OrRequestEviction(id.toString(), "DRIVER_DECLINE_ASSIGNMENT", "idem-1");
    }

    @Test
    void declineAssignment_throwsWhenRetryCacheEvictionFallbackCannotBeRecorded() {
        UUID id = UUID.randomUUID();
        DriverDeclineResponse stored = new DriverDeclineResponse(id.toString(), "CONFIRMED");
        when(idempotentCommandService.execute(anyString(), eq("idem-1"), eq(id.toString()), anyString(), any(), any()))
                .thenReturn(stored);
        CacheInvalidationFailedException failure =
                new CacheInvalidationFailedException(id.toString(), new IllegalStateException("outbox unavailable"));
        doThrow(failure)
                .when(orderCacheEvictionService)
                .evictC2OrRequestEviction(id.toString(), "DRIVER_DECLINE_ASSIGNMENT", "idem-1");

        assertThatThrownBy(() -> service.declineAssignment("driver-1", id.toString(), "idem-1"))
                .isInstanceOf(CacheInvalidationFailedException.class);
    }

    @Test
    void declineAssignment_throwsInvalidTransitionAfterPickup() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id))
                .thenReturn(Optional.of(order(id, OrderStatus.OUT_FOR_DELIVERY, "driver-1")));

        assertThatThrownBy(() -> service.declineAssignment("driver-1", id.toString(), "idem-1"))
                .isInstanceOf(OrderInvalidTransitionException.class)
                .hasMessageContaining("DRIVER_DECLINE");

        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void declineAssignment_throwsDriverNotAssignedWhenDriverDoesNotMatch() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id))
                .thenReturn(Optional.of(order(id, OrderStatus.CONFIRMED, "other-driver")));

        assertThatThrownBy(() -> service.declineAssignment("driver-1", id.toString(), "idem-1"))
                .isInstanceOf(DriverNotAssignedException.class);

        verify(orderRepository, never()).saveAndFlush(any());
    }

    private OrderLifecycleResult lifecycleResult(OrderStatus from, OrderStatus to) {
        return lifecycleResult(from, to, OffsetDateTime.now());
    }

    private OrderLifecycleResult lifecycleResult(OrderStatus from, OrderStatus to, OffsetDateTime changedAt) {
        OffsetDateTime now = OffsetDateTime.now();
        OrderEntity order = OrderEntity.builder()
                .id(UUID.randomUUID())
                .customerId("customer-1")
                .storeId("store-1")
                .driverId("driver-1")
                .cartId("cart-1")
                .status(to)
                .scheduleType(ScheduleType.ASAP)
                .subtotalAmount(new BigDecimal("33000.00"))
                .itemDiscountAmount(BigDecimal.ZERO)
                .orderDiscountAmount(BigDecimal.ZERO)
                .deliveryFeeAmount(new BigDecimal("3000.00"))
                .totalAmount(new BigDecimal("36000.00"))
                .currency("UZS")
                .deliveryAddressJson("{\"street\":\"Amir Temur 25\",\"city\":\"Tashkent\",\"apartment\":\"12\"}")
                .deliveryLat(new BigDecimal("41.311100"))
                .deliveryLng(new BigDecimal("69.279700"))
                .storeLat(new BigDecimal("41.320000"))
                .storeLng(new BigDecimal("69.240000"))
                .createdAt(now)
                .updatedAt(now)
                .build();
        return new OrderLifecycleResult(order, from, to, changedAt);
    }

    private VerificationCodeCacheDto verificationCode(String code) {
        return VerificationCodeCacheDto.builder()
                .code(code)
                .generatedAt(OffsetDateTime.now().toString())
                .build();
    }

    private OrderStatusHistoryEntity historyWithCode(String code) {
        return historyWithMetadata("{\"code\":\"" + code + "\"}");
    }

    private OrderStatusHistoryEntity historyWithNullMetadata() {
        return OrderStatusHistoryEntity.builder()
                .id(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .fromStatus(OrderStatus.OUT_FOR_DELIVERY)
                .toStatus(OrderStatus.ARRIVED)
                .changedByType("DRIVER")
                .reason("DRIVER_ARRIVED")
                .metadataJson(null)
                .build();
    }

    private OrderStatusHistoryEntity historyWithMetadata(String metadataJson) {
        return OrderStatusHistoryEntity.builder()
                .id(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .fromStatus(OrderStatus.OUT_FOR_DELIVERY)
                .toStatus(OrderStatus.ARRIVED)
                .changedByType("DRIVER")
                .reason("DRIVER_ARRIVED")
                .metadataJson(metadataJson)
                .build();
    }

    private OrderEntity order(UUID id, OrderStatus status, String driverId) {
        OffsetDateTime now = OffsetDateTime.now();
        return OrderEntity.builder()
                .id(id)
                .customerId("customer-1")
                .storeId("store-1")
                .driverId(driverId)
                .cartId("cart-1")
                .status(status)
                .scheduleType(ScheduleType.ASAP)
                .subtotalAmount(new BigDecimal("33000.00"))
                .itemDiscountAmount(BigDecimal.ZERO)
                .orderDiscountAmount(BigDecimal.ZERO)
                .deliveryFeeAmount(new BigDecimal("3000.00"))
                .totalAmount(new BigDecimal("36000.00"))
                .currency("UZS")
                .deliveryAddressJson("{\"street\":\"Amir Temur 25\",\"city\":\"Tashkent\",\"apartment\":\"12\"}")
                .deliveryLat(new BigDecimal("41.311100"))
                .deliveryLng(new BigDecimal("69.279700"))
                .storeLat(new BigDecimal("41.320000"))
                .storeLng(new BigDecimal("69.240000"))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private OrderItemEntity item(UUID orderId, int lineNumber, String productId, String productName) {
        return OrderItemEntity.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .lineNumber(lineNumber)
                .productId(productId)
                .productName(productName)
                .quantity(new BigDecimal("1.000"))
                .unit(ProductUnit.PCS)
                .unitPriceAmount(new BigDecimal("12000.00"))
                .grossAmount(new BigDecimal("12000.00"))
                .discountAmount(BigDecimal.ZERO)
                .netAmount(new BigDecimal("12000.00"))
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
