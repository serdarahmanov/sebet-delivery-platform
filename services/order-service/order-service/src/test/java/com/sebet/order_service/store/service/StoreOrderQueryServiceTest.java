package com.sebet.order_service.store.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.OrderItem;
import com.sebet.order_service.cache.dto.OrderProposalsCacheDto;
import com.sebet.order_service.cache.dto.RedisOrder;
import com.sebet.order_service.cache.repository.OrderProposalsRedisRepository;
import com.sebet.order_service.cache.repository.OrderRedisRepository;
import com.sebet.order_service.cache.repository.OrderStatusRedisRepository;
import com.sebet.order_service.cache.repository.StoreActiveOrdersRedisRepository;
import com.sebet.order_service.cache.repository.StoreScheduledOrdersRedisRepository;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderItemEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.repository.OrderItemRepository;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderCancellationReason;
import com.sebet.order_service.shared.enums.OrderCancelledBy;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ProductUnit;
import com.sebet.order_service.shared.enums.ScheduleType;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
import com.sebet.order_service.store.dto.response.StoreActiveOrderItemResponse;
import com.sebet.order_service.store.dto.response.StoreOrderDetailResponse;
import com.sebet.order_service.store.dto.response.StoreOrderHistoryItemResponse;
import com.sebet.order_service.store.dto.response.StoreOrderStatusResponse;
import com.sebet.order_service.store.dto.response.StoreScheduledOrderItemResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoreOrderQueryServiceTest {

    private final StoreActiveOrdersRedisRepository storeActiveOrdersRedisRepository =
            mock(StoreActiveOrdersRedisRepository.class);
    private final StoreScheduledOrdersRedisRepository storeScheduledOrdersRedisRepository =
            mock(StoreScheduledOrdersRedisRepository.class);
    private final OrderRedisRepository orderRedisRepository = mock(OrderRedisRepository.class);
    private final OrderStatusRedisRepository orderStatusRedisRepository = mock(OrderStatusRedisRepository.class);
    private final OrderProposalsRedisRepository orderProposalsRedisRepository = mock(OrderProposalsRedisRepository.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
    private final OrderStatusHistoryRepository orderStatusHistoryRepository = mock(OrderStatusHistoryRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final StoreOrderQueryService service = new StoreOrderQueryService(
            storeActiveOrdersRedisRepository,
            storeScheduledOrdersRedisRepository,
            orderRedisRepository,
            orderStatusRedisRepository,
            orderProposalsRedisRepository,
            orderRepository,
            orderItemRepository,
            orderStatusHistoryRepository,
            objectMapper
    );

    @Test
    void getOrderHistory_returnsDeliveredAndCancelledRows() {
        UUID deliveredId = UUID.randomUUID();
        UUID cancelledId = UUID.randomUUID();
        OrderEntity delivered = entity(deliveredId, OrderStatus.DELIVERED);
        delivered.setDeliveredAt(OffsetDateTime.now());
        OrderEntity cancelled = entity(cancelledId, OrderStatus.CANCELLED);
        cancelled.setCancelledAt(OffsetDateTime.now());
        cancelled.setCancelledBy(OrderCancelledBy.STORE);
        cancelled.setCancellationReason(OrderCancellationReason.OUT_OF_STOCK);

        when(orderRepository.findByStoreIdAndStatusIn(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(delivered, cancelled)));
        when(orderItemRepository.findByOrderIdInOrderByOrderIdAscLineNumberAsc(any()))
                .thenReturn(List.of(itemForOrder(deliveredId), itemForOrder(cancelledId)));

        Page<StoreOrderHistoryItemResponse> result =
                service.getOrderHistory("store-1", PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).route())
                .isEqualTo(StoreOrderHistoryItemResponse.OrderDetailRoute.DELIVERED);
        assertThat(result.getContent().get(1).route())
                .isEqualTo(StoreOrderHistoryItemResponse.OrderDetailRoute.CANCELLED);
        assertThat(result.getContent().get(1).cancellation()).isNotNull();
    }

    @Test
    void getActiveOrders_usesRedisSnapshotAndStatus() {
        String orderId = UUID.randomUUID().toString();
        when(storeActiveOrdersRedisRepository.getAll("store-1")).thenReturn(Set.of(orderId));
        when(orderRedisRepository.findById(orderId)).thenReturn(Optional.of(snapshot(orderId, "store-1")));
        when(orderStatusRedisRepository.findById(orderId))
                .thenReturn(Optional.of(new OrderStatusRedisRepository.Entry("CONFIRMED", "customer-1", "store-1")));

        List<StoreActiveOrderItemResponse> result = service.getActiveOrders("store-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(result.get(0).items()).hasSize(1);
        verify(orderRepository, never()).findByStoreIdAndStatusInOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void getActiveOrders_fallsBackToDatabaseWhenRedisSetIsEmpty() {
        UUID orderId = UUID.randomUUID();
        when(storeActiveOrdersRedisRepository.getAll("store-1")).thenReturn(Set.of());
        when(orderRepository.findByStoreIdAndStatusInOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(entity(orderId, OrderStatus.PENDING)));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId))
                .thenReturn(List.of(itemForOrder(orderId)));

        List<StoreActiveOrderItemResponse> result = service.getActiveOrders("store-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void getActiveOrders_fallsBackToDatabaseWhenCachedStatusIsMissing() {
        String staleOrderId = UUID.randomUUID().toString();
        UUID dbOrderId = UUID.randomUUID();
        when(storeActiveOrdersRedisRepository.getAll("store-1")).thenReturn(Set.of(staleOrderId));
        when(orderRedisRepository.findById(staleOrderId)).thenReturn(Optional.of(snapshot(staleOrderId, "store-1")));
        when(orderStatusRedisRepository.findById(staleOrderId)).thenReturn(Optional.empty());
        when(orderRepository.findByStoreIdAndStatusInOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(entity(dbOrderId, OrderStatus.CONFIRMED)));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(dbOrderId))
                .thenReturn(List.of(itemForOrder(dbOrderId)));

        List<StoreActiveOrderItemResponse> result = service.getActiveOrders("store-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void getActiveOrders_fallsBackToDatabaseWhenRedisSetIsPartiallyStale() {
        String cachedOrderId = UUID.randomUUID().toString();
        String missingOrderId = UUID.randomUUID().toString();
        UUID dbOrderId = UUID.randomUUID();
        when(storeActiveOrdersRedisRepository.getAll("store-1")).thenReturn(Set.of(cachedOrderId, missingOrderId));
        when(orderRedisRepository.findById(cachedOrderId)).thenReturn(Optional.of(snapshot(cachedOrderId, "store-1")));
        when(orderRedisRepository.findById(missingOrderId)).thenReturn(Optional.empty());
        when(orderStatusRedisRepository.findById(cachedOrderId))
                .thenReturn(Optional.of(new OrderStatusRedisRepository.Entry("PENDING", "customer-1", "store-1")));
        when(orderRepository.findByStoreIdAndStatusInOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(entity(dbOrderId, OrderStatus.READY_FOR_PICKUP)));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(dbOrderId))
                .thenReturn(List.of(itemForOrder(dbOrderId)));

        List<StoreActiveOrderItemResponse> result = service.getActiveOrders("store-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
    }

    @Test
    void getScheduledOrders_readsRedisInScheduledOrder() {
        String orderId = UUID.randomUUID().toString();
        RedisOrder snapshot = snapshot(orderId, "store-1");
        snapshot.setEstimatedDeliveryAt("2026-07-08T10:00:00Z");
        when(storeScheduledOrdersRedisRepository.getAll("store-1")).thenReturn(Set.of(orderId));
        when(orderRedisRepository.findById(orderId)).thenReturn(Optional.of(snapshot));

        List<StoreScheduledOrderItemResponse> result = service.getScheduledOrders("store-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(OrderStatus.SCHEDULED);
        assertThat(result.get(0).scheduledFor()).isEqualTo("2026-07-08T10:00:00Z");
    }

    @Test
    void getScheduledOrders_fallsBackToDatabaseWhenRedisSetIsPartiallyStale() {
        String cachedOrderId = UUID.randomUUID().toString();
        String missingOrderId = UUID.randomUUID().toString();
        UUID dbOrderId = UUID.randomUUID();
        RedisOrder snapshot = snapshot(cachedOrderId, "store-1");
        snapshot.setEstimatedDeliveryAt("2026-07-08T10:00:00Z");
        when(storeScheduledOrdersRedisRepository.getAll("store-1")).thenReturn(Set.of(cachedOrderId, missingOrderId));
        when(orderRedisRepository.findById(cachedOrderId)).thenReturn(Optional.of(snapshot));
        when(orderRedisRepository.findById(missingOrderId)).thenReturn(Optional.empty());
        when(orderRepository.findByStoreIdAndStatusInOrderByScheduledForAsc(any(), any()))
                .thenReturn(List.of(entity(dbOrderId, OrderStatus.SCHEDULED)));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(dbOrderId))
                .thenReturn(List.of(itemForOrder(dbOrderId)));

        List<StoreScheduledOrderItemResponse> result = service.getScheduledOrders("store-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(OrderStatus.SCHEDULED);
    }

    @Test
    void getOrderDetail_returnsDbDetailWithTimelineAndCancellation() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = entity(orderId, OrderStatus.CANCELLED);
        order.setCancelledAt(OffsetDateTime.now());
        order.setCancelledBy(OrderCancelledBy.STORE);
        order.setCancellationReason(OrderCancellationReason.STORE_REJECTED);
        when(orderRepository.findByIdAndStoreId(orderId, "store-1")).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId)).thenReturn(List.of(itemForOrder(orderId)));
        when(orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(orderId))
                .thenReturn(List.of(historyEntry(orderId, null, OrderStatus.PENDING, OffsetDateTime.now())));

        StoreOrderDetailResponse result = service.getOrderDetail("store-1", orderId.toString());

        assertThat(result.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(result.items()).hasSize(1);
        assertThat(result.cancellation()).isNotNull();
        assertThat(result.timeline()).hasSize(4);
        assertThat(result.timeline().get(0).occurredAt()).isNotNull();
    }

    @Test
    void getOrderDetail_includesPendingProposalWhenAwaitingCustomerResponse() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = entity(orderId, OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        OrderProposalsCacheDto proposal = OrderProposalsCacheDto.builder()
                .orderId(orderId.toString())
                .proposedAt("2026-07-07T10:00:00Z")
                .items(List.of(OrderProposalsCacheDto.ProposedItem.builder()
                        .productId("product-1")
                        .productName("Apples")
                        .requestedQuantity(new BigDecimal("2.000"))
                        .unit(ProductUnit.KG)
                        .availableQuantity(new BigDecimal("1.000"))
                        .build()))
                .build();
        when(orderRepository.findByIdAndStoreId(orderId, "store-1")).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId)).thenReturn(List.of(itemForOrder(orderId)));
        when(orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of());
        when(orderProposalsRedisRepository.find(orderId.toString())).thenReturn(Optional.of(proposal));

        StoreOrderDetailResponse result = service.getOrderDetail("store-1", orderId.toString());

        assertThat(result.pendingProposal()).isNotNull();
        assertThat(result.pendingProposal().items()).hasSize(1);
    }

    @Test
    void getOrderStatus_usesCachedStatusAfterStatusCacheOwnershipCheck() {
        String orderId = UUID.randomUUID().toString();
        when(orderStatusRedisRepository.findById(orderId))
                .thenReturn(Optional.of(new OrderStatusRedisRepository.Entry("READY_FOR_PICKUP", "customer-1", "store-1")));

        StoreOrderStatusResponse result = service.getOrderStatus("store-1", orderId);

        assertThat(result.status()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
        verify(orderRedisRepository, never()).findById(orderId);
        verify(orderRepository, never()).findByIdAndStoreId(any(), any());
    }

    @Test
    void getOrderStatus_hidesOrderWhenCachedStatusBelongsToAnotherStore() {
        String orderId = UUID.randomUUID().toString();
        when(orderStatusRedisRepository.findById(orderId))
                .thenReturn(Optional.of(new OrderStatusRedisRepository.Entry("PENDING", "customer-1", "other-store")));

        assertThatThrownBy(() -> service.getOrderStatus("store-1", orderId))
                .isInstanceOf(OrderNotFoundException.class);
        verify(orderRedisRepository, never()).findById(orderId);
    }

    @Test
    void getOrderStatus_fallsBackToDatabaseWhenCacheMisses() {
        UUID orderId = UUID.randomUUID();
        when(orderStatusRedisRepository.findById(orderId.toString())).thenReturn(Optional.empty());
        when(orderRepository.findByIdAndStoreId(orderId, "store-1"))
                .thenReturn(Optional.of(entity(orderId, OrderStatus.CONFIRMED)));

        StoreOrderStatusResponse result = service.getOrderStatus("store-1", orderId.toString());

        assertThat(result.status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void getOrderStatus_fallsBackToDatabaseWhenCachedStatusIsInvalid() {
        UUID orderId = UUID.randomUUID();
        when(orderStatusRedisRepository.findById(orderId.toString()))
                .thenReturn(Optional.of(new OrderStatusRedisRepository.Entry("NOT_A_STATUS", "customer-1", "store-1")));
        when(orderRepository.findByIdAndStoreId(orderId, "store-1"))
                .thenReturn(Optional.of(entity(orderId, OrderStatus.ARRIVED)));

        StoreOrderStatusResponse result = service.getOrderStatus("store-1", orderId.toString());

        assertThat(result.status()).isEqualTo(OrderStatus.ARRIVED);
    }

    private RedisOrder snapshot(String orderId, String storeId) {
        return RedisOrder.builder()
                .orderId(orderId)
                .userId("customer-1")
                .storeId(storeId)
                .storeName("Test Store")
                .totalAmount(new BigDecimal("36000.00"))
                .items(List.of(OrderItem.builder()
                        .productId("product-1")
                        .name("Apples")
                        .imageUrl("https://cdn/apple.png")
                        .quantity(new BigDecimal("2.500"))
                        .unitPrice(new BigDecimal("12000.00"))
                        .subtotal(new BigDecimal("24000.00"))
                        .build()))
                .createdAt("2026-07-07T09:00:00Z")
                .build();
    }

    private OrderEntity entity(UUID id, OrderStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        OrderEntity order = new OrderEntity();
        order.setId(id);
        order.setCustomerId("customer-1");
        order.setStoreId("store-1");
        order.setCartId("cart-1");
        order.setStatus(status);
        order.setScheduleType(status == OrderStatus.SCHEDULED ? ScheduleType.SCHEDULED : ScheduleType.IMMEDIATE);
        order.setScheduledFor(status == OrderStatus.SCHEDULED ? now.plusDays(1) : null);
        order.setSubtotalAmount(new BigDecimal("33000.00"));
        order.setItemDiscountAmount(BigDecimal.ZERO);
        order.setOrderDiscountAmount(BigDecimal.ZERO);
        order.setDeliveryFeeAmount(new BigDecimal("3000.00"));
        order.setTotalAmount(new BigDecimal("36000.00"));
        order.setCurrency("UZS");
        order.setDeliveryAddressJson("{\"street\":\"Amir Temur 25\",\"city\":\"Tashkent\"}");
        order.setDeliveryLat(new BigDecimal("41.311100"));
        order.setDeliveryLng(new BigDecimal("69.279700"));
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        return order;
    }

    private OrderItemEntity itemForOrder(UUID orderId) {
        OrderItemEntity item = new OrderItemEntity();
        item.setId(UUID.randomUUID());
        item.setOrderId(orderId);
        item.setLineNumber(1);
        item.setProductId("product-1");
        item.setProductName("Apples");
        item.setQuantity(new BigDecimal("2.500"));
        item.setUnit(ProductUnit.KG);
        item.setUnitPriceAmount(new BigDecimal("12000.00"));
        item.setGrossAmount(new BigDecimal("24000.00"));
        item.setDiscountAmount(BigDecimal.ZERO);
        item.setNetAmount(new BigDecimal("24000.00"));
        item.setImageUrl("https://cdn/apple.png");
        item.setCreatedAt(OffsetDateTime.now());
        return item;
    }

    private OrderStatusHistoryEntity historyEntry(UUID orderId, OrderStatus from, OrderStatus to, OffsetDateTime at) {
        OrderStatusHistoryEntity history = new OrderStatusHistoryEntity();
        history.setId(UUID.randomUUID());
        history.setOrderId(orderId);
        history.setFromStatus(from);
        history.setToStatus(to);
        history.setChangedByType("STORE");
        history.setCreatedAt(at);
        return history;
    }
}
