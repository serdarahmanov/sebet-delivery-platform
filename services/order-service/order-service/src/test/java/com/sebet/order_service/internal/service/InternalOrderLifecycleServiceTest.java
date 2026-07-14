package com.sebet.order_service.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.internal.dto.request.SystemCancelOrderRequest;
import com.sebet.order_service.internal.dto.request.UpdateAfterProposalItemRequest;
import com.sebet.order_service.internal.dto.request.UpdateAfterProposalRequest;
import com.sebet.order_service.internal.dto.response.ActivateScheduledOrderResponse;
import com.sebet.order_service.internal.dto.response.CancelActiveProposalResponse;
import com.sebet.order_service.internal.dto.response.CancelProposalResponse;
import com.sebet.order_service.internal.dto.response.SystemCancelOrderResponse;
import com.sebet.order_service.internal.dto.response.UpdateAfterProposalResponse;
import com.sebet.order_service.order.service.OrderLifecycleResult;
import com.sebet.order_service.order.service.OrderLifecycleService;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.shared.enums.OrderCancellationReason;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ProductUnit;
import com.sebet.order_service.shared.enums.ScheduleType;
import com.sebet.order_service.shared.idempotency.IdempotentCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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

class InternalOrderLifecycleServiceTest {

    private final OrderLifecycleService orderLifecycleService = mock(OrderLifecycleService.class);
    private final IdempotentCommandService idempotentCommandService = mock(IdempotentCommandService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final InternalOrderLifecycleService service = new InternalOrderLifecycleService(
            orderLifecycleService,
            idempotentCommandService,
            objectMapper
    );

    @BeforeEach
    void runIdempotentOperation() {
        when(idempotentCommandService.execute(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
    }

    @Test
    void activateScheduled_recordsIdempotentCommandAndUpdatesRedisWithSameOrder() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id);
        OffsetDateTime changedAt = OffsetDateTime.parse("2026-07-10T10:00:00Z");
        when(orderLifecycleService.activateScheduledWithoutRedisUpdate(id.toString()))
                .thenReturn(new OrderLifecycleResult(
                        order,
                        OrderStatus.SCHEDULED,
                        OrderStatus.PENDING,
                        changedAt
                ));

        ActivateScheduledOrderResponse response = service.activateScheduled(id.toString(), "idem-1");

        assertThat(response.orderId()).isEqualTo(id.toString());
        assertThat(response.status()).isEqualTo("PENDING");
        verify(idempotentCommandService).execute(
                eq(OrderLifecycleService.INTERNAL_ACTIVATE_SCHEDULED_ACTION),
                eq("idem-1"),
                eq(id.toString()),
                eq("orderId=" + id),
                eq(ActivateScheduledOrderResponse.class),
                any()
        );
        verify(orderLifecycleService).updateScheduledActivationRedisViews(order, changedAt, "idem-1");
        verify(orderLifecycleService, never()).updateScheduledActivationRedisViews(id.toString(), "idem-1");
    }

    @Test
    void activateScheduled_retriesRedisUpdateFromDatabaseWhenIdempotencyRecordExists() {
        UUID id = UUID.randomUUID();
        ActivateScheduledOrderResponse stored = new ActivateScheduledOrderResponse(id.toString(), "PENDING");
        when(idempotentCommandService.execute(anyString(), eq("idem-1"), eq(id.toString()), anyString(), any(), any()))
                .thenReturn(stored);

        ActivateScheduledOrderResponse response = service.activateScheduled(id.toString(), "idem-1");

        assertThat(response).isEqualTo(stored);
        verify(orderLifecycleService, never()).activateScheduledWithoutRedisUpdate(anyString());
        verify(orderLifecycleService).updateScheduledActivationRedisViews(id.toString(), "idem-1");
    }

    @Test
    void systemCancel_recordsIdempotentCommandAndEvictsCancelledViews() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id);
        OffsetDateTime changedAt = OffsetDateTime.parse("2026-07-10T10:00:00Z");
        when(orderLifecycleService.systemCancelWithoutRedisUpdate(
                eq(id.toString()),
                eq(OrderCancellationReason.PAYMENT_FAILED),
                eq("{\"reason\":\"PAYMENT_FAILED\"}")
        )).thenReturn(new OrderLifecycleResult(
                order,
                OrderStatus.PENDING,
                OrderStatus.CANCELLED,
                changedAt
        ));

        SystemCancelOrderResponse response = service.systemCancel(
                id.toString(),
                new SystemCancelOrderRequest("PAYMENT_FAILED"),
                "idem-1"
        );

        assertThat(response.orderId()).isEqualTo(id.toString());
        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.reason()).isEqualTo("PAYMENT_FAILED");
        assertThat(response.cancelledAt()).isEqualTo("2026-07-10T10:00Z");
        verify(idempotentCommandService).execute(
                eq(OrderLifecycleService.INTERNAL_SYSTEM_CANCEL_ACTION),
                eq("idem-1"),
                eq(id.toString()),
                eq("orderId=" + id + ";reason=PAYMENT_FAILED"),
                eq(SystemCancelOrderResponse.class),
                any()
        );
        verify(orderLifecycleService).evictSystemCancelledRedisViews(order, changedAt, "idem-1");
        verify(orderLifecycleService, never()).evictSystemCancelledRedisViews(id.toString(), "idem-1");
    }

    @Test
    void systemCancel_retriesRedisCleanupWhenIdempotencyRecordExists() {
        UUID id = UUID.randomUUID();
        SystemCancelOrderResponse stored = new SystemCancelOrderResponse(
                id.toString(),
                "CANCELLED",
                "PAYMENT_FAILED",
                "2026-07-10T10:00Z"
        );
        when(idempotentCommandService.execute(anyString(), eq("idem-1"), eq(id.toString()), anyString(), any(), any()))
                .thenReturn(stored);

        SystemCancelOrderResponse response = service.systemCancel(
                id.toString(),
                new SystemCancelOrderRequest("PAYMENT_FAILED"),
                "idem-1"
        );

        assertThat(response).isEqualTo(stored);
        verify(orderLifecycleService, never()).systemCancelWithoutRedisUpdate(
                anyString(), any(OrderCancellationReason.class), any());
        verify(orderLifecycleService).evictSystemCancelledRedisViews(id.toString(), "idem-1");
    }

    @Test
    void adminCancel_recordsAdminIdempotentCommandAndEvictsCancelledViews() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id);
        OffsetDateTime changedAt = OffsetDateTime.parse("2026-07-10T10:00:00Z");
        when(orderLifecycleService.adminCancelWithoutRedisUpdate(
                eq(id.toString()),
                eq(OrderCancellationReason.SYSTEM_ERROR),
                eq("{\"reason\":\"SYSTEM_ERROR\"}")
        )).thenReturn(new OrderLifecycleResult(
                order,
                OrderStatus.OUT_FOR_DELIVERY,
                OrderStatus.CANCELLED,
                changedAt
        ));

        SystemCancelOrderResponse response = service.adminCancel(
                id.toString(),
                new SystemCancelOrderRequest("SYSTEM_ERROR"),
                "idem-1"
        );

        assertThat(response.orderId()).isEqualTo(id.toString());
        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.reason()).isEqualTo("SYSTEM_ERROR");
        assertThat(response.cancelledAt()).isEqualTo("2026-07-10T10:00Z");
        verify(idempotentCommandService).execute(
                eq(OrderLifecycleService.INTERNAL_ADMIN_CANCEL_ACTION),
                eq("idem-1"),
                eq(id.toString()),
                eq("orderId=" + id + ";reason=SYSTEM_ERROR"),
                eq(SystemCancelOrderResponse.class),
                any()
        );
        verify(orderLifecycleService).evictAdminCancelledRedisViews(order, changedAt, "idem-1");
        verify(orderLifecycleService, never()).evictAdminCancelledRedisViews(id.toString(), "idem-1");
    }

    @Test
    void adminCancel_retriesRedisCleanupWhenIdempotencyRecordExists() {
        UUID id = UUID.randomUUID();
        SystemCancelOrderResponse stored = new SystemCancelOrderResponse(
                id.toString(),
                "CANCELLED",
                "SYSTEM_ERROR",
                "2026-07-10T10:00Z"
        );
        when(idempotentCommandService.execute(anyString(), eq("idem-1"), eq(id.toString()), anyString(), any(), any()))
                .thenReturn(stored);

        SystemCancelOrderResponse response = service.adminCancel(
                id.toString(),
                new SystemCancelOrderRequest("SYSTEM_ERROR"),
                "idem-1"
        );

        assertThat(response).isEqualTo(stored);
        verify(orderLifecycleService, never()).adminCancelWithoutRedisUpdate(
                anyString(), any(OrderCancellationReason.class), any());
        verify(orderLifecycleService).evictAdminCancelledRedisViews(id.toString(), "idem-1");
    }

    @Test
    void cancelActiveProposal_recordsIdempotentCommandAndUpdatesRedisWithSameOrder() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id);
        OffsetDateTime changedAt = OffsetDateTime.parse("2026-07-10T10:00:00Z");
        when(orderLifecycleService.cancelActiveProposalWithoutRedisUpdate(id.toString()))
                .thenReturn(new OrderLifecycleResult(
                        order,
                        OrderStatus.AWAITING_CUSTOMER_RESPONSE,
                        OrderStatus.CONFIRMED,
                        changedAt
                ));

        CancelActiveProposalResponse response = service.cancelActiveProposal(id.toString(), "idem-1");

        assertThat(response.orderId()).isEqualTo(id.toString());
        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.cancelledAt()).isEqualTo("2026-07-10T10:00Z");
        verify(idempotentCommandService).execute(
                eq(OrderLifecycleService.INTERNAL_CANCEL_ACTIVE_PROPOSAL_ACTION),
                eq("idem-1"),
                eq(id.toString()),
                eq("orderId=" + id),
                eq(CancelActiveProposalResponse.class),
                any()
        );
        verify(orderLifecycleService).updateCancelActiveProposalRedisViews(order, "idem-1");
        verify(orderLifecycleService, never()).updateCancelActiveProposalRedisViews(id.toString(), "idem-1");
    }

    @Test
    void cancelActiveProposal_retriesRedisUpdateFromDatabaseWhenIdempotencyRecordExists() {
        UUID id = UUID.randomUUID();
        CancelActiveProposalResponse stored = new CancelActiveProposalResponse(
                id.toString(),
                "CONFIRMED",
                "2026-07-10T10:00Z"
        );
        when(idempotentCommandService.execute(anyString(), eq("idem-1"), eq(id.toString()), anyString(), any(), any()))
                .thenReturn(stored);

        CancelActiveProposalResponse response = service.cancelActiveProposal(id.toString(), "idem-1");

        assertThat(response).isEqualTo(stored);
        verify(orderLifecycleService, never()).cancelActiveProposalWithoutRedisUpdate(anyString());
        verify(orderLifecycleService).updateCancelActiveProposalRedisViews(id.toString(), "idem-1");
    }

    @Test
    void cancelProposalAndOrder_recordsIdempotentCommandAndEvictsCancelledViews() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id);
        OffsetDateTime changedAt = OffsetDateTime.parse("2026-07-10T10:00:00Z");
        when(orderLifecycleService.cancelProposalAndOrderWithoutRedisUpdate(id.toString()))
                .thenReturn(new OrderLifecycleResult(
                        order,
                        OrderStatus.AWAITING_CUSTOMER_RESPONSE,
                        OrderStatus.CANCELLED,
                        changedAt
                ));

        CancelProposalResponse response = service.cancelProposalAndOrder(id.toString(), "idem-1");

        assertThat(response.orderId()).isEqualTo(id.toString());
        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.cancelledAt()).isEqualTo("2026-07-10T10:00Z");
        verify(idempotentCommandService).execute(
                eq(OrderLifecycleService.INTERNAL_CANCEL_PROPOSAL_AND_ORDER_ACTION),
                eq("idem-1"),
                eq(id.toString()),
                eq("orderId=" + id),
                eq(CancelProposalResponse.class),
                any()
        );
        verify(orderLifecycleService).evictCancelProposalAndOrderRedisViews(order, changedAt, "idem-1");
        verify(orderLifecycleService, never()).evictCancelProposalAndOrderRedisViews(id.toString(), "idem-1");
    }

    @Test
    void cancelProposalAndOrder_retriesRedisCleanupWhenIdempotencyRecordExists() {
        UUID id = UUID.randomUUID();
        CancelProposalResponse stored = new CancelProposalResponse(
                id.toString(),
                "CANCELLED",
                "2026-07-10T10:00Z"
        );
        when(idempotentCommandService.execute(anyString(), eq("idem-1"), eq(id.toString()), anyString(), any(), any()))
                .thenReturn(stored);

        CancelProposalResponse response = service.cancelProposalAndOrder(id.toString(), "idem-1");

        assertThat(response).isEqualTo(stored);
        verify(orderLifecycleService, never()).cancelProposalAndOrderWithoutRedisUpdate(anyString());
        verify(orderLifecycleService).evictCancelProposalAndOrderRedisViews(id.toString(), "idem-1");
    }

    @Test
    void updateAfterProposal_recordsIdempotentCommandAndUpdatesRedisWithSameOrder() {
        UUID id = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        OrderEntity order = order(id);
        OffsetDateTime changedAt = OffsetDateTime.parse("2026-07-10T10:00:00Z");
        UpdateAfterProposalRequest request = updateAfterProposalRequest(proposalId);
        when(orderLifecycleService.applyProposalUpdateWithoutRedisUpdate(id.toString(), request))
                .thenReturn(new OrderLifecycleResult(
                        order,
                        OrderStatus.AWAITING_CUSTOMER_RESPONSE,
                        OrderStatus.CONFIRMED,
                        changedAt
                ));

        UpdateAfterProposalResponse response = service.updateAfterProposal(id.toString(), request, "idem-1");

        assertThat(response.orderId()).isEqualTo(id.toString());
        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.proposalId()).isEqualTo(proposalId.toString());
        assertThat(response.appliedAt()).isEqualTo("2026-07-10T10:00Z");
        verify(idempotentCommandService).execute(
                eq(OrderLifecycleService.INTERNAL_UPDATE_AFTER_PROPOSAL_ACTION),
                eq("idem-1"),
                eq(id.toString()),
                anyString(),
                eq(UpdateAfterProposalResponse.class),
                any()
        );
        verify(orderLifecycleService).updateApplyProposalRedisViews(order, "idem-1");
        verify(orderLifecycleService, never()).updateApplyProposalRedisViews(id.toString(), "idem-1");
    }

    @Test
    void updateAfterProposal_retriesRedisUpdateFromDatabaseWhenIdempotencyRecordExists() {
        UUID id = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        UpdateAfterProposalRequest request = updateAfterProposalRequest(proposalId);
        UpdateAfterProposalResponse stored = new UpdateAfterProposalResponse(
                id.toString(),
                "CONFIRMED",
                proposalId.toString(),
                "2026-07-10T10:00Z"
        );
        when(idempotentCommandService.execute(anyString(), eq("idem-1"), eq(id.toString()), anyString(), any(), any()))
                .thenReturn(stored);

        UpdateAfterProposalResponse response = service.updateAfterProposal(id.toString(), request, "idem-1");

        assertThat(response).isEqualTo(stored);
        verify(orderLifecycleService, never()).applyProposalUpdateWithoutRedisUpdate(anyString(), any());
        verify(orderLifecycleService).updateApplyProposalRedisViews(id.toString(), "idem-1");
    }

    @Test
    void systemCancel_rejectsUnsupportedReason() {
        assertThatThrownBy(() -> service.systemCancel(
                UUID.randomUUID().toString(),
                new SystemCancelOrderRequest("STORE_REJECTED"),
                "idem-1"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported internal cancellation reason");

        verify(idempotentCommandService, never()).execute(any(), any(), any(), any(), any(), any());
    }

    private OrderEntity order(UUID id) {
        OffsetDateTime now = OffsetDateTime.now();
        return OrderEntity.builder()
                .id(id)
                .customerId("customer-1")
                .storeId("store-1")
                .cartId("cart-1")
                .status(OrderStatus.PENDING)
                .scheduleType(ScheduleType.SCHEDULED)
                .scheduledFor(now.plusHours(2))
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
    }

    private UpdateAfterProposalRequest updateAfterProposalRequest(UUID proposalId) {
        return new UpdateAfterProposalRequest(
                proposalId,
                "calc-1",
                "UZS",
                new BigDecimal("10000.00"),
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("3000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("11500.00"),
                List.of("PROMO500"),
                List.of(new UpdateAfterProposalItemRequest(
                        "p1",
                        new BigDecimal("1.000"),
                        ProductUnit.KG,
                        new BigDecimal("10000.00"),
                        new BigDecimal("10000.00"),
                        new BigDecimal("1000.00"),
                        new BigDecimal("9000.00")
                ))
        );
    }
}
