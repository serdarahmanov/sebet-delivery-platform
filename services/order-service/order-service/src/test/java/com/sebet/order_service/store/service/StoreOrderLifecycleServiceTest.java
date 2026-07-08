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
import com.sebet.order_service.store.dto.request.RejectOrderRequest;
import com.sebet.order_service.store.dto.response.StoreRejectOrderResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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

class StoreOrderLifecycleServiceTest {

    private final OrderLifecycleService orderLifecycleService = mock(OrderLifecycleService.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final StoreOrderLifecycleService service = new StoreOrderLifecycleService(
            orderLifecycleService,
            orderRepository,
            orderItemRepository,
            objectMapper
    );

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
