package com.sebet.order_service.internal.service;

import com.sebet.order_service.cache.service.OrderCacheEvictionService;
import com.sebet.order_service.internal.dto.request.AssignDriverRequest;
import com.sebet.order_service.internal.dto.request.UnassignDriverRequest;
import com.sebet.order_service.internal.dto.response.AssignDriverResponse;
import com.sebet.order_service.internal.dto.response.UnassignDriverResponse;
import com.sebet.order_service.order.event.OrderEventOutboxWriter;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ScheduleType;
import com.sebet.order_service.shared.exception.CacheInvalidationFailedException;
import com.sebet.order_service.shared.exception.OrderInvalidTransitionException;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
import com.sebet.order_service.shared.idempotency.IdempotentCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalDriverAssignmentServiceTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderEventOutboxWriter orderEventOutboxWriter = mock(OrderEventOutboxWriter.class);
    private final IdempotentCommandService idempotentCommandService = mock(IdempotentCommandService.class);
    private final OrderCacheEvictionService orderCacheEvictionService = mock(OrderCacheEvictionService.class);

    private final InternalDriverAssignmentService service = new InternalDriverAssignmentService(
            orderRepository,
            orderEventOutboxWriter,
            idempotentCommandService,
            orderCacheEvictionService
    );

    @BeforeEach
    void runIdempotentOperation() {
        when(idempotentCommandService.execute(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
    }

    @Test
    void assignDriver_setsDriverAndWritesAssignedOutboxEvent() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.CONFIRMED, null);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);

        AssignDriverResponse response = service.assignDriver(
                id.toString(),
                new AssignDriverRequest("driver-1"),
                "idem-1"
        );

        assertThat(response.orderId()).isEqualTo(id.toString());
        assertThat(response.driverId()).isEqualTo("driver-1");
        assertThat(response.driverAssignedAt()).isNotNull();
        assertThat(order.getDriverId()).isEqualTo("driver-1");
        assertThat(order.getDriverAssignedAt()).isNotNull();
        verify(orderRepository).saveAndFlush(order);
        verify(orderCacheEvictionService).evictC2OrRequestEviction(id.toString(), "INTERNAL_ASSIGN_DRIVER", "idem-1");
        verify(orderEventOutboxWriter).saveDriverAssigned(
                eq(order),
                eq("driver-1"),
                any(OffsetDateTime.class)
        );
    }

    @Test
    void assignDriver_replacesDifferentDriverAndWritesReplacedOutboxEvent() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.READY_FOR_PICKUP, "old-driver");
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);

        AssignDriverResponse response = service.assignDriver(
                id.toString(),
                new AssignDriverRequest("new-driver"),
                "idem-1"
        );

        assertThat(response.driverId()).isEqualTo("new-driver");
        assertThat(order.getDriverId()).isEqualTo("new-driver");
        verify(orderEventOutboxWriter).saveDriverReplaced(
                eq(order),
                eq("old-driver"),
                eq("new-driver"),
                any(OffsetDateTime.class)
        );
        verify(orderEventOutboxWriter, never()).saveDriverAssigned(any(), any(), any());
    }

    @Test
    void assignDriver_sameDriverIsIdempotentAndDoesNotWriteOutboxEvent() {
        UUID id = UUID.randomUUID();
        OffsetDateTime assignedAt = OffsetDateTime.parse("2026-07-09T10:00:00Z");
        OrderEntity order = order(id, OrderStatus.CONFIRMED, "driver-1");
        order.setDriverAssignedAt(assignedAt);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));

        AssignDriverResponse response = service.assignDriver(
                id.toString(),
                new AssignDriverRequest("driver-1"),
                "idem-1"
        );

        assertThat(response.driverId()).isEqualTo("driver-1");
        assertThat(response.driverAssignedAt()).isEqualTo(assignedAt.toString());
        verify(orderRepository, never()).saveAndFlush(any());
        verify(orderEventOutboxWriter, never()).saveDriverAssigned(any(), any(), any());
        verify(orderEventOutboxWriter, never()).saveDriverReplaced(any(), any(), any(), any());
        verify(orderCacheEvictionService).evictC2OrRequestEviction(id.toString(), "INTERNAL_ASSIGN_DRIVER", "idem-1");
    }

    @Test
    void assignDriver_throwsWhenCacheEvictionFallbackCannotBeRecorded() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.CONFIRMED, null);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);
        CacheInvalidationFailedException failure =
                new CacheInvalidationFailedException(id.toString(), new IllegalStateException("outbox unavailable"));
        org.mockito.Mockito.doThrow(failure)
                .when(orderCacheEvictionService)
                .evictC2OrRequestEviction(id.toString(), "INTERNAL_ASSIGN_DRIVER", "idem-1");

        assertThatThrownBy(() -> service.assignDriver(
                id.toString(),
                new AssignDriverRequest("driver-1"),
                "idem-1"
        )).isInstanceOf(CacheInvalidationFailedException.class);

        assertThat(order.getDriverId()).isEqualTo("driver-1");
        verify(orderEventOutboxWriter).saveDriverAssigned(
                eq(order),
                eq("driver-1"),
                any(OffsetDateTime.class)
        );
    }

    @Test
    void assignDriver_retriesCacheEvictionWhenIdempotencyRecordExists() {
        UUID id = UUID.randomUUID();
        AssignDriverResponse stored = new AssignDriverResponse(
                id.toString(),
                "driver-1",
                "2026-07-09T10:00:00Z"
        );
        when(idempotentCommandService.execute(anyString(), eq("idem-1"), eq(id.toString()), anyString(), any(), any()))
                .thenReturn(stored);

        AssignDriverResponse response = service.assignDriver(
                id.toString(),
                new AssignDriverRequest("driver-1"),
                "idem-1"
        );

        assertThat(response).isEqualTo(stored);
        verify(orderRepository, never()).findById(any());
        verify(orderEventOutboxWriter, never()).saveDriverAssigned(any(), any(), any());
        verify(orderCacheEvictionService).evictC2OrRequestEviction(id.toString(), "INTERNAL_ASSIGN_DRIVER", "idem-1");
    }

    @Test
    void assignDriver_rejectsTerminalOrder() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order(id, OrderStatus.DELIVERED, null)));

        assertThatThrownBy(() -> service.assignDriver(
                id.toString(),
                new AssignDriverRequest("driver-1"),
                "idem-1"
        ))
                .isInstanceOf(OrderInvalidTransitionException.class)
                .hasMessageContaining("ASSIGN_DRIVER");

        verify(orderRepository, never()).saveAndFlush(any());
        verify(orderEventOutboxWriter, never()).saveDriverAssigned(any(), any(), any());
    }

    @Test
    void unassignDriver_clearsDriverAndWritesUnassignedOutboxEvent() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.OUT_FOR_DELIVERY, "driver-1");
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);

        UnassignDriverResponse response = service.unassignDriver(
                id.toString(),
                new UnassignDriverRequest("ADMIN_OVERRIDE"),
                "idem-1"
        );

        assertThat(response.orderId()).isEqualTo(id.toString());
        assertThat(response.previousDriverId()).isEqualTo("driver-1");
        assertThat(response.status()).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(response.reason()).isEqualTo("ADMIN_OVERRIDE");
        assertThat(order.getDriverId()).isNull();
        assertThat(order.getDriverAssignedAt()).isNull();
        verify(orderCacheEvictionService).evictC2OrRequestEviction(id.toString(), "INTERNAL_UNASSIGN_DRIVER", "idem-1");
        verify(orderEventOutboxWriter).saveDriverUnassigned(
                eq(order),
                eq("driver-1"),
                any(OffsetDateTime.class),
                eq("ADMIN_OVERRIDE")
        );
    }

    @Test
    void unassignDriver_rejectsWhenNoDriverAssigned() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order(id, OrderStatus.CONFIRMED, null)));

        assertThatThrownBy(() -> service.unassignDriver(
                id.toString(),
                new UnassignDriverRequest("ADMIN_OVERRIDE"),
                "idem-1"
        ))
                .isInstanceOf(OrderInvalidTransitionException.class)
                .hasMessageContaining("UNASSIGN_DRIVER");

        verify(orderRepository, never()).saveAndFlush(any());
        verify(orderEventOutboxWriter, never()).saveDriverUnassigned(any(), any(), any(), any());
    }

    @Test
    void unassignDriver_rejectsTerminalOrder() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order(id, OrderStatus.CANCELLED, "driver-1")));

        assertThatThrownBy(() -> service.unassignDriver(
                id.toString(),
                new UnassignDriverRequest("ADMIN_OVERRIDE"),
                "idem-1"
        ))
                .isInstanceOf(OrderInvalidTransitionException.class)
                .hasMessageContaining("UNASSIGN_DRIVER");

        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void unassignDriver_throwsWhenCacheEvictionFallbackCannotBeRecorded() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.CONFIRMED, "driver-1");
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);
        CacheInvalidationFailedException failure =
                new CacheInvalidationFailedException(id.toString(), new IllegalStateException("outbox unavailable"));
        org.mockito.Mockito.doThrow(failure)
                .when(orderCacheEvictionService)
                .evictC2OrRequestEviction(id.toString(), "INTERNAL_UNASSIGN_DRIVER", "idem-1");

        assertThatThrownBy(() -> service.unassignDriver(
                id.toString(),
                new UnassignDriverRequest("ADMIN_OVERRIDE"),
                "idem-1"
        )).isInstanceOf(CacheInvalidationFailedException.class);

        assertThat(order.getDriverId()).isNull();
        verify(orderEventOutboxWriter).saveDriverUnassigned(
                eq(order),
                eq("driver-1"),
                any(OffsetDateTime.class),
                eq("ADMIN_OVERRIDE")
        );
    }

    @Test
    void unassignDriver_retriesCacheEvictionWhenIdempotencyRecordExists() {
        UUID id = UUID.randomUUID();
        UnassignDriverResponse stored = new UnassignDriverResponse(
                id.toString(),
                "driver-1",
                "CONFIRMED",
                "ADMIN_OVERRIDE"
        );
        when(idempotentCommandService.execute(anyString(), eq("idem-1"), eq(id.toString()), anyString(), any(), any()))
                .thenReturn(stored);

        UnassignDriverResponse response = service.unassignDriver(
                id.toString(),
                new UnassignDriverRequest("ADMIN_OVERRIDE"),
                "idem-1"
        );

        assertThat(response).isEqualTo(stored);
        verify(orderRepository, never()).findById(any());
        verify(orderEventOutboxWriter, never()).saveDriverUnassigned(any(), any(), any(), any());
        verify(orderCacheEvictionService).evictC2OrRequestEviction(id.toString(), "INTERNAL_UNASSIGN_DRIVER", "idem-1");
    }

    @Test
    void assignDriver_throwsNotFoundForInvalidOrderId() {
        assertThatThrownBy(() -> service.assignDriver(
                "not-a-uuid",
                new AssignDriverRequest("driver-1"),
                "idem-1"
        ))
                .isInstanceOf(OrderNotFoundException.class);

        verify(orderRepository, never()).findById(any());
    }

    private OrderEntity order(UUID id, OrderStatus status, String driverId) {
        OffsetDateTime now = OffsetDateTime.now();
        return OrderEntity.builder()
                .id(id)
                .customerId("customer-1")
                .storeId("store-1")
                .driverId(driverId)
                .driverAssignedAt(driverId == null ? null : now)
                .cartId("cart-1")
                .status(status)
                .scheduleType(ScheduleType.ASAP)
                .subtotalAmount(new BigDecimal("33000.00"))
                .itemDiscountAmount(BigDecimal.ZERO)
                .orderDiscountAmount(BigDecimal.ZERO)
                .deliveryFeeAmount(new BigDecimal("3000.00"))
                .totalAmount(new BigDecimal("36000.00"))
                .currency("UZS")
                .deliveryAddressJson("{\"street\":\"Amir Temur 25\"}")
                .deliveryLat(new BigDecimal("41.311100"))
                .deliveryLng(new BigDecimal("69.279700"))
                .storeLat(new BigDecimal("41.320000"))
                .storeLng(new BigDecimal("69.240000"))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
