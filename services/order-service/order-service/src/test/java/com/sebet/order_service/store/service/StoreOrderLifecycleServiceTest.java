package com.sebet.order_service.store.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.order.service.OrderLifecycleResult;
import com.sebet.order_service.order.service.OrderLifecycleService;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderItemEntity;
import com.sebet.order_service.persistence.repository.OrderItemRepository;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.shared.enums.OrderCancellationReason;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ProductUnit;
import com.sebet.order_service.shared.enums.ScheduleType;
import com.sebet.order_service.shared.exception.OrderInvalidTransitionException;
import com.sebet.order_service.shared.idempotency.IdempotentCommandService;
import com.sebet.order_service.store.dto.request.ProposeOrderChangesRequest;
import com.sebet.order_service.store.dto.request.RejectOrderRequest;
import com.sebet.order_service.store.dto.request.StoreCancelOrderRequest;
import com.sebet.order_service.store.dto.response.StoreCancelOrderResponse;
import com.sebet.order_service.store.dto.response.StoreProposeOrderChangesResponse;
import com.sebet.order_service.store.dto.response.StoreRejectOrderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoreOrderLifecycleServiceTest {

    private final OrderLifecycleService orderLifecycleService = mock(OrderLifecycleService.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IdempotentCommandService idempotentCommandService = mock(IdempotentCommandService.class);

    private final StoreOrderLifecycleService service = new StoreOrderLifecycleService(
            orderLifecycleService,
            orderRepository,
            orderItemRepository,
            objectMapper,
            idempotentCommandService
    );

    @BeforeEach
    void runIdempotentOperation() {
        when(idempotentCommandService.execute(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
    }

    @Test
    void rejectOrder_outOfStockValidatesItemsAndPassesMetadataToLifecycle() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = order(orderId, OrderStatus.PENDING);
        when(orderRepository.findByIdAndStoreId(orderId, "store-1")).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId)).thenReturn(List.of(
                item(orderId, "product-1", "Apples", new BigDecimal("2.000"), ProductUnit.KG)
        ));
        when(orderLifecycleService.storeReject(
                any(),
                any(),
                any(),
                any()
        )).thenReturn(new OrderLifecycleResult(
                order,
                OrderStatus.PENDING,
                OrderStatus.CANCELLED,
                OffsetDateTime.parse("2026-07-07T10:00:00Z")
        ));

        StoreRejectOrderResponse response = service.rejectOrder(
                "store-1",
                orderId.toString(),
                outOfStockRequest(new BigDecimal("1.000"))
        );

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(response.reason()).isEqualTo(OrderCancellationReason.OUT_OF_STOCK);

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(orderLifecycleService).storeReject(
                eq(orderId.toString()),
                eq("store-1"),
                eq(OrderCancellationReason.OUT_OF_STOCK),
                metadataCaptor.capture()
        );
        assertThat(metadataCaptor.getValue()).contains("\"note\":\"short stock\"");
        assertThat(metadataCaptor.getValue()).contains("\"productId\":\"product-1\"");
        assertThat(metadataCaptor.getValue()).contains("\"availableQuantity\":1.000");
    }

    @Test
    void rejectOrder_storeRejectedDoesNotAllowOutOfStockItems() {
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> service.rejectOrder(
                "store-1",
                orderId.toString(),
                new RejectOrderRequest(
                        RejectOrderRequest.RejectionReason.STORE_REJECTED,
                        List.of(outOfStockItem("product-1", new BigDecimal("2.000"), ProductUnit.KG, null)),
                        null
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outOfStockItems is only allowed");

        verify(orderRepository, never()).findByIdAndStoreId(any(), any());
        verify(orderLifecycleService, never()).storeReject(any(), any(), any(), any());
    }

    @Test
    void rejectOrder_outOfStockRequiresItems() {
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> service.rejectOrder(
                "store-1",
                orderId.toString(),
                new RejectOrderRequest(RejectOrderRequest.RejectionReason.OUT_OF_STOCK, List.of(), null)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outOfStockItems is required");

        verify(orderRepository, never()).findByIdAndStoreId(any(), any());
        verify(orderLifecycleService, never()).storeReject(any(), any(), any(), any());
    }

    @Test
    void rejectOrder_outOfStockRejectsNullItems() {
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> service.rejectOrder(
                "store-1",
                orderId.toString(),
                new RejectOrderRequest(
                        RejectOrderRequest.RejectionReason.OUT_OF_STOCK,
                        new ArrayList<>() {{
                            add(null);
                        }},
                        null
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain null items");

        verify(orderRepository, never()).findByIdAndStoreId(any(), any());
        verify(orderLifecycleService, never()).storeReject(any(), any(), any(), any());
    }

    @Test
    void rejectOrder_outOfStockRejectsUnknownProduct() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(orderId, "store-1"))
                .thenReturn(Optional.of(order(orderId, OrderStatus.PENDING)));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId)).thenReturn(List.of(
                item(orderId, "product-1", "Apples", new BigDecimal("2.000"), ProductUnit.KG)
        ));

        assertThatThrownBy(() -> service.rejectOrder(
                "store-1",
                orderId.toString(),
                new RejectOrderRequest(
                        RejectOrderRequest.RejectionReason.OUT_OF_STOCK,
                        List.of(outOfStockItem("product-2", new BigDecimal("2.000"), ProductUnit.KG, null)),
                        null
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to order");

        verify(orderLifecycleService, never()).storeReject(any(), any(), any(), any());
    }

    @Test
    void rejectOrder_outOfStockRejectsMismatchedRequestedQuantity() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(orderId, "store-1"))
                .thenReturn(Optional.of(order(orderId, OrderStatus.PENDING)));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId)).thenReturn(List.of(
                item(orderId, "product-1", "Apples", new BigDecimal("2.000"), ProductUnit.KG)
        ));

        assertThatThrownBy(() -> service.rejectOrder(
                "store-1",
                orderId.toString(),
                new RejectOrderRequest(
                        RejectOrderRequest.RejectionReason.OUT_OF_STOCK,
                        List.of(outOfStockItem("product-1", new BigDecimal("3.000"), ProductUnit.KG, null)),
                        null
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestedQuantity does not match");

        verify(orderLifecycleService, never()).storeReject(any(), any(), any(), any());
    }

    @Test
    void rejectOrder_outOfStockRejectsMismatchedUnit() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(orderId, "store-1"))
                .thenReturn(Optional.of(order(orderId, OrderStatus.PENDING)));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId)).thenReturn(List.of(
                item(orderId, "product-1", "Apples", new BigDecimal("2.000"), ProductUnit.KG)
        ));

        assertThatThrownBy(() -> service.rejectOrder(
                "store-1",
                orderId.toString(),
                new RejectOrderRequest(
                        RejectOrderRequest.RejectionReason.OUT_OF_STOCK,
                        List.of(outOfStockItem("product-1", new BigDecimal("2.000"), ProductUnit.PCS, null)),
                        null
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unit does not match");

        verify(orderLifecycleService, never()).storeReject(any(), any(), any(), any());
    }

    @Test
    void rejectOrder_outOfStockRejectsAvailableQuantityGreaterThanOrEqualToRequested() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(orderId, "store-1"))
                .thenReturn(Optional.of(order(orderId, OrderStatus.PENDING)));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId)).thenReturn(List.of(
                item(orderId, "product-1", "Apples", new BigDecimal("2.000"), ProductUnit.KG)
        ));

        assertThatThrownBy(() -> service.rejectOrder(
                "store-1",
                orderId.toString(),
                outOfStockRequest(new BigDecimal("2.000"))
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("availableQuantity must be less");

        verify(orderLifecycleService, never()).storeReject(any(), any(), any(), any());
    }

    @Test
    void rejectOrder_outOfStockRejectsDuplicateProductIds() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(orderId, "store-1"))
                .thenReturn(Optional.of(order(orderId, OrderStatus.PENDING)));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId)).thenReturn(List.of(
                item(orderId, "product-1", "Apples", new BigDecimal("2.000"), ProductUnit.KG)
        ));

        assertThatThrownBy(() -> service.rejectOrder(
                "store-1",
                orderId.toString(),
                new RejectOrderRequest(
                        RejectOrderRequest.RejectionReason.OUT_OF_STOCK,
                        List.of(
                                outOfStockItem("product-1", new BigDecimal("2.000"), ProductUnit.KG, null),
                                outOfStockItem("product-1", new BigDecimal("2.000"), ProductUnit.KG, null)
                        ),
                        null
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");

        verify(orderLifecycleService, never()).storeReject(any(), any(), any(), any());
    }

    @Test
    void rejectOrder_outOfStockRejectsNonPendingOrderBeforeItemValidation() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(orderId, "store-1"))
                .thenReturn(Optional.of(order(orderId, OrderStatus.CONFIRMED)));

        assertThatThrownBy(() -> service.rejectOrder(
                "store-1",
                orderId.toString(),
                outOfStockRequest(null)
        )).isInstanceOf(OrderInvalidTransitionException.class);

        verify(orderItemRepository, never()).findByOrderIdOrderByLineNumberAsc(any());
        verify(orderLifecycleService, never()).storeReject(any(), any(), any(), any());
    }

    @Test
    void cancelOrder_mapsStoreClosedReasonAndPassesMetadataToLifecycle() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = order(orderId, OrderStatus.CANCELLED);
        when(orderLifecycleService.storeCancelWithoutRedisUpdate(
                any(),
                any(),
                any(),
                any()
        )).thenReturn(new OrderLifecycleResult(
                order,
                OrderStatus.CONFIRMED,
                OrderStatus.CANCELLED,
                OffsetDateTime.parse("2026-07-09T10:00:00Z")
        ));

        StoreCancelOrderResponse response = service.cancelOrder(
                "store-1",
                orderId.toString(),
                new StoreCancelOrderRequest(StoreCancelOrderRequest.CancellationReason.STORE_CLOSED, "closed early"),
                "idem-1"
        );

        assertThat(response.orderId()).isEqualTo(orderId.toString());
        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(response.reason()).isEqualTo(OrderCancellationReason.STORE_CLOSED);
        assertThat(response.cancelledAt()).isEqualTo("2026-07-09T10:00Z");

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(idempotentCommandService).execute(
                eq("STORE_CANCEL_ORDER"),
                eq("idem-1"),
                eq(orderId.toString()),
                eq("storeId=store-1;orderId=" + orderId + ";reason=STORE_CLOSED;note=closed early"),
                eq(StoreCancelOrderResponse.class),
                any()
        );
        verify(orderLifecycleService).storeCancelWithoutRedisUpdate(
                eq(orderId.toString()),
                eq("store-1"),
                eq(OrderCancellationReason.STORE_CLOSED),
                metadataCaptor.capture()
        );
        assertThat(metadataCaptor.getValue()).contains("\"note\":\"closed early\"");
        verify(orderLifecycleService).evictStoreCancelledRedisViews(orderId.toString(), "store-1", "idem-1");
    }

    @Test
    void cancelOrder_mapsUnableToFulfilReason() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = order(orderId, OrderStatus.CANCELLED);
        when(orderLifecycleService.storeCancelWithoutRedisUpdate(any(), any(), any(), any()))
                .thenReturn(new OrderLifecycleResult(
                        order,
                        OrderStatus.AWAITING_CUSTOMER_RESPONSE,
                        OrderStatus.CANCELLED,
                        OffsetDateTime.parse("2026-07-09T10:00:00Z")
                ));

        service.cancelOrder(
                "store-1",
                orderId.toString(),
                new StoreCancelOrderRequest(
                        StoreCancelOrderRequest.CancellationReason.STORE_UNABLE_TO_FULFIL,
                        null
                ),
                "idem-1"
        );

        verify(orderLifecycleService).storeCancelWithoutRedisUpdate(
                eq(orderId.toString()),
                eq("store-1"),
                eq(OrderCancellationReason.STORE_UNABLE_TO_FULFIL),
                any()
        );
    }

    @Test
    void cancelOrder_replaysStoredResponseAndRetriesRedisEvictionWhenIdempotencyRecordExists() {
        UUID orderId = UUID.randomUUID();
        StoreCancelOrderResponse storedResponse = new StoreCancelOrderResponse(
                orderId.toString(),
                OrderStatus.CANCELLED,
                OrderCancellationReason.STORE_CLOSED,
                "2026-07-09T10:00Z"
        );
        when(idempotentCommandService.execute(
                eq("STORE_CANCEL_ORDER"),
                eq("idem-1"),
                eq(orderId.toString()),
                anyString(),
                eq(StoreCancelOrderResponse.class),
                any()
        )).thenReturn(storedResponse);

        StoreCancelOrderResponse response = service.cancelOrder(
                "store-1",
                orderId.toString(),
                new StoreCancelOrderRequest(StoreCancelOrderRequest.CancellationReason.STORE_CLOSED, "closed early"),
                "idem-1"
        );

        assertThat(response).isSameAs(storedResponse);
        verify(orderLifecycleService, never()).storeCancelWithoutRedisUpdate(any(), any(), any(), any());
        verify(orderLifecycleService).evictStoreCancelledRedisViews(orderId.toString(), "store-1", "idem-1");
    }

    @Test
    void cancelOrder_requiresRequestBody() {
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> service.cancelOrder("store-1", orderId.toString(), null, "idem-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cancel request body is required");

        verify(idempotentCommandService, never()).execute(any(), any(), any(), any(), any(), any());
        verify(orderLifecycleService, never()).storeCancelWithoutRedisUpdate(any(), any(), any(), any());
    }

    @Test
    void cancelOrder_requiresReason() {
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> service.cancelOrder(
                "store-1",
                orderId.toString(),
                new StoreCancelOrderRequest(null, null),
                "idem-1"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason is required");

        verify(idempotentCommandService, never()).execute(any(), any(), any(), any(), any(), any());
        verify(orderLifecycleService, never()).storeCancelWithoutRedisUpdate(any(), any(), any(), any());
    }

    @Test
    void proposeChanges_blankIdempotencyKey_throwsIllegalArgument() {
        when(idempotentCommandService.execute(anyString(), eq("  "), anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalArgumentException("Idempotency-Key must not be blank"));

        assertThatThrownBy(() -> service.proposeChanges(
                "store-1", UUID.randomUUID().toString(),
                proposeRequest("product-1", new BigDecimal("3.000"), ProductUnit.KG, null),
                "  "
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key must not be blank");

        verify(orderLifecycleService, never()).storeProposeChangesWithoutRedisUpdate(any(), any(), any(), any());
        verify(orderLifecycleService, never()).updateProposeChangesRedisViews(any(), any(), any());
    }

    // ── proposeChanges ────────────────────────────────────────────────────────

    @Test
    void proposeChanges_happyPath_transitionsToAwaitingAndReturnsResponse() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = order(orderId, OrderStatus.CONFIRMED);
        when(orderRepository.findByIdAndStoreId(orderId, "store-1")).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId)).thenReturn(List.of(
                item(orderId, "product-1", "Apples", new BigDecimal("3.000"), ProductUnit.KG)
        ));
        when(orderLifecycleService.storeProposeChangesWithoutRedisUpdate(
                any(), any(), any(), any()
        )).thenReturn(new OrderLifecycleResult(
                order,
                OrderStatus.CONFIRMED,
                OrderStatus.AWAITING_CUSTOMER_RESPONSE,
                OffsetDateTime.parse("2026-07-09T10:00:00Z")
        ));

        StoreProposeOrderChangesResponse response = service.proposeChanges(
                "store-1",
                orderId.toString(),
                proposeRequest("product-1", new BigDecimal("3.000"), ProductUnit.KG, new BigDecimal("1.000")),
                "idem-1"
        );

        assertThat(response.orderId()).isEqualTo(orderId.toString());
        assertThat(response.status()).isEqualTo(OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        assertThat(response.proposedAt()).isEqualTo("2026-07-09T10:00Z");
        assertThat(response.changes()).hasSize(1);
        assertThat(response.changes().get(0).productId()).isEqualTo("product-1");
        assertThat(response.changes().get(0).availableQuantity()).isEqualByComparingTo("1.000");

        verify(orderLifecycleService).storeProposeChangesWithoutRedisUpdate(
                eq(orderId.toString()), eq("store-1"), any(), any());
        verify(orderLifecycleService).updateProposeChangesRedisViews(orderId.toString(), "store-1", "idem-1");
    }

    @Test
    void proposeChanges_idempotentReplay_returnsStoredResponseAndStillUpdatesRedis() {
        UUID orderId = UUID.randomUUID();
        StoreProposeOrderChangesResponse storedResponse = new StoreProposeOrderChangesResponse(
                orderId.toString(),
                OrderStatus.AWAITING_CUSTOMER_RESPONSE,
                "2026-07-09T10:00:00Z",
                List.of()
        );
        when(idempotentCommandService.execute(
                eq("STORE_PROPOSE_CHANGES"), eq("idem-1"), eq(orderId.toString()),
                anyString(), eq(StoreProposeOrderChangesResponse.class), any()
        )).thenReturn(storedResponse);

        StoreProposeOrderChangesResponse response = service.proposeChanges(
                "store-1",
                orderId.toString(),
                proposeRequest("product-1", new BigDecimal("3.000"), ProductUnit.KG, null),
                "idem-1"
        );

        assertThat(response).isSameAs(storedResponse);
        verify(orderLifecycleService, never()).storeProposeChangesWithoutRedisUpdate(any(), any(), any(), any());
        verify(orderLifecycleService).updateProposeChangesRedisViews(orderId.toString(), "store-1", "idem-1");
    }

    @Test
    void proposeChanges_orderNotConfirmed_throwsInvalidTransition() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(orderId, "store-1"))
                .thenReturn(Optional.of(order(orderId, OrderStatus.AWAITING_CUSTOMER_RESPONSE)));

        assertThatThrownBy(() -> service.proposeChanges(
                "store-1",
                orderId.toString(),
                proposeRequest("product-1", new BigDecimal("3.000"), ProductUnit.KG, null),
                "idem-1"
        )).isInstanceOf(OrderInvalidTransitionException.class);

        verify(orderLifecycleService, never()).storeProposeChangesWithoutRedisUpdate(any(), any(), any(), any());
        verify(orderLifecycleService, never()).updateProposeChangesRedisViews(any(), any(), any());
    }

    @Test
    void proposeChanges_orderNotFound_throwsOrderNotFound() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(orderId, "store-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.proposeChanges(
                "store-1",
                orderId.toString(),
                proposeRequest("product-1", new BigDecimal("3.000"), ProductUnit.KG, null),
                "idem-1"
        )).isInstanceOf(com.sebet.order_service.shared.exception.OrderNotFoundException.class);

        verify(orderLifecycleService, never()).storeProposeChangesWithoutRedisUpdate(any(), any(), any(), any());
    }

    @Test
    void proposeChanges_duplicateProductId_throwsIllegalArgument() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(orderId, "store-1"))
                .thenReturn(Optional.of(order(orderId, OrderStatus.CONFIRMED)));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId)).thenReturn(List.of(
                item(orderId, "product-1", "Apples", new BigDecimal("3.000"), ProductUnit.KG)
        ));

        ProposeOrderChangesRequest request = new ProposeOrderChangesRequest(List.of(
                new ProposeOrderChangesRequest.ProposedItemChange(
                        "product-1", "Apples", new BigDecimal("3.000"), ProductUnit.KG, null),
                new ProposeOrderChangesRequest.ProposedItemChange(
                        "product-1", "Apples", new BigDecimal("3.000"), ProductUnit.KG, null)
        ));

        assertThatThrownBy(() -> service.proposeChanges("store-1", orderId.toString(), request, "idem-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void proposeChanges_unknownProductId_throwsIllegalArgument() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(orderId, "store-1"))
                .thenReturn(Optional.of(order(orderId, OrderStatus.CONFIRMED)));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId)).thenReturn(List.of(
                item(orderId, "product-1", "Apples", new BigDecimal("3.000"), ProductUnit.KG)
        ));

        assertThatThrownBy(() -> service.proposeChanges(
                "store-1", orderId.toString(),
                proposeRequest("product-99", new BigDecimal("3.000"), ProductUnit.KG, null),
                "idem-1"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to order");
    }

    @Test
    void proposeChanges_productNameMismatch_throwsIllegalArgument() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(orderId, "store-1"))
                .thenReturn(Optional.of(order(orderId, OrderStatus.CONFIRMED)));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId)).thenReturn(List.of(
                item(orderId, "product-1", "Apples", new BigDecimal("3.000"), ProductUnit.KG)
        ));

        ProposeOrderChangesRequest request = new ProposeOrderChangesRequest(List.of(
                new ProposeOrderChangesRequest.ProposedItemChange(
                        "product-1", "Oranges", new BigDecimal("3.000"), ProductUnit.KG, null)
        ));

        assertThatThrownBy(() -> service.proposeChanges(
                "store-1", orderId.toString(), request, "idem-1"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match order item name");
    }

    @Test
    void proposeChanges_unitMismatch_throwsIllegalArgument() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(orderId, "store-1"))
                .thenReturn(Optional.of(order(orderId, OrderStatus.CONFIRMED)));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId)).thenReturn(List.of(
                item(orderId, "product-1", "Apples", new BigDecimal("3.000"), ProductUnit.KG)
        ));

        assertThatThrownBy(() -> service.proposeChanges(
                "store-1", orderId.toString(),
                proposeRequest("product-1", new BigDecimal("3.000"), ProductUnit.PCS, null),
                "idem-1"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unit does not match");
    }

    @Test
    void proposeChanges_requestedQuantityMismatch_throwsIllegalArgument() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(orderId, "store-1"))
                .thenReturn(Optional.of(order(orderId, OrderStatus.CONFIRMED)));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId)).thenReturn(List.of(
                item(orderId, "product-1", "Apples", new BigDecimal("3.000"), ProductUnit.KG)
        ));

        assertThatThrownBy(() -> service.proposeChanges(
                "store-1", orderId.toString(),
                proposeRequest("product-1", new BigDecimal("5.000"), ProductUnit.KG, null),
                "idem-1"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match order item quantity");
    }

    @Test
    void proposeChanges_availableQuantityEqualToRequested_throwsIllegalArgument() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(orderId, "store-1"))
                .thenReturn(Optional.of(order(orderId, OrderStatus.CONFIRMED)));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId)).thenReturn(List.of(
                item(orderId, "product-1", "Apples", new BigDecimal("3.000"), ProductUnit.KG)
        ));

        assertThatThrownBy(() -> service.proposeChanges(
                "store-1", orderId.toString(),
                proposeRequest("product-1", new BigDecimal("3.000"), ProductUnit.KG, new BigDecimal("3.000")),
                "idem-1"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be less than requestedQuantity");
    }

    @Test
    void proposeChanges_availableQuantityZero_throwsIllegalArgument() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(orderId, "store-1"))
                .thenReturn(Optional.of(order(orderId, OrderStatus.CONFIRMED)));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId)).thenReturn(List.of(
                item(orderId, "product-1", "Apples", new BigDecimal("3.000"), ProductUnit.KG)
        ));

        assertThatThrownBy(() -> service.proposeChanges(
                "store-1", orderId.toString(),
                proposeRequest("product-1", new BigDecimal("3.000"), ProductUnit.KG, BigDecimal.ZERO),
                "idem-1"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    private ProposeOrderChangesRequest proposeRequest(
            String productId,
            BigDecimal requestedQuantity,
            ProductUnit unit,
            BigDecimal availableQuantity
    ) {
        return new ProposeOrderChangesRequest(List.of(
                new ProposeOrderChangesRequest.ProposedItemChange(
                        productId, "Apples", requestedQuantity, unit, availableQuantity)
        ));
    }

    private RejectOrderRequest outOfStockRequest(BigDecimal availableQuantity) {
        return new RejectOrderRequest(
                RejectOrderRequest.RejectionReason.OUT_OF_STOCK,
                List.of(outOfStockItem("product-1", new BigDecimal("2.000"), ProductUnit.KG, availableQuantity)),
                "short stock"
        );
    }

    private RejectOrderRequest.OutOfStockItem outOfStockItem(
            String productId,
            BigDecimal requestedQuantity,
            ProductUnit unit,
            BigDecimal availableQuantity
    ) {
        return new RejectOrderRequest.OutOfStockItem(
                productId,
                "Apples",
                requestedQuantity,
                unit,
                availableQuantity
        );
    }

    private OrderEntity order(UUID id, OrderStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return OrderEntity.builder()
                .id(id)
                .customerId("customer-1")
                .storeId("store-1")
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
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private OrderItemEntity item(
            UUID orderId,
            String productId,
            String productName,
            BigDecimal quantity,
            ProductUnit unit
    ) {
        return OrderItemEntity.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .lineNumber(1)
                .productId(productId)
                .productName(productName)
                .quantity(quantity)
                .unit(unit)
                .unitPriceAmount(new BigDecimal("12000.00"))
                .grossAmount(new BigDecimal("24000.00"))
                .discountAmount(BigDecimal.ZERO)
                .netAmount(new BigDecimal("24000.00"))
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
