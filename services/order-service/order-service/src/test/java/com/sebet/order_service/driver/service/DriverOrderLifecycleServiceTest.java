package com.sebet.order_service.driver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.VerificationCodeCacheDto;
import com.sebet.order_service.cache.repository.VerificationCodeRedisRepository;
import com.sebet.order_service.driver.dto.response.DriverArriveResponse;
import com.sebet.order_service.driver.dto.response.DriverCompleteDeliveryResponse;
import com.sebet.order_service.driver.dto.response.DriverPickupResponse;
import com.sebet.order_service.order.service.OrderLifecycleResult;
import com.sebet.order_service.order.service.OrderLifecycleService;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ScheduleType;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
import com.sebet.order_service.shared.exception.VerificationCodeNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriverOrderLifecycleServiceTest {

    private final OrderLifecycleService orderLifecycleService = mock(OrderLifecycleService.class);
    private final VerificationCodeRedisRepository verificationCodeRedisRepository = mock(VerificationCodeRedisRepository.class);
    private final OrderStatusHistoryRepository orderStatusHistoryRepository = mock(OrderStatusHistoryRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final DriverOrderLifecycleService service = new DriverOrderLifecycleService(
            orderLifecycleService,
            verificationCodeRedisRepository,
            orderStatusHistoryRepository,
            objectMapper
    );

    // ── confirmPickup ────────────────────────────────────────────────────────

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
                .scheduleType(ScheduleType.IMMEDIATE)
                .subtotalAmount(new BigDecimal("33000.00"))
                .itemDiscountAmount(BigDecimal.ZERO)
                .orderDiscountAmount(BigDecimal.ZERO)
                .deliveryFeeAmount(new BigDecimal("3000.00"))
                .totalAmount(new BigDecimal("36000.00"))
                .currency("UZS")
                .deliveryAddressJson("{\"street\":\"Amir Temur 25\"}")
                .deliveryLat(new BigDecimal("41.311100"))
                .deliveryLng(new BigDecimal("69.279700"))
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
}
