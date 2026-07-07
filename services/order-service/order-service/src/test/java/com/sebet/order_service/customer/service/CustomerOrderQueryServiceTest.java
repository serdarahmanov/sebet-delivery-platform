package com.sebet.order_service.customer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.DeliveryAddress;
import com.sebet.order_service.cache.dto.DriverInfo;
import com.sebet.order_service.cache.dto.OrderItem;
import com.sebet.order_service.cache.dto.OrderProposalsCacheDto;
import com.sebet.order_service.cache.dto.OrderTimelineEntry;
import com.sebet.order_service.cache.dto.RedisOrder;
import com.sebet.order_service.cache.dto.RedisOrderTracking;
import com.sebet.order_service.cache.dto.StoreLocation;
import com.sebet.order_service.cache.dto.VerificationCodeCacheDto;
import com.sebet.order_service.cache.repository.ActiveOrdersRedisRepository;
import com.sebet.order_service.cache.repository.OrderProposalsRedisRepository;
import com.sebet.order_service.cache.repository.OrderRedisRepository;
import com.sebet.order_service.cache.repository.OrderStatusRedisRepository;
import com.sebet.order_service.cache.repository.OrderTimelineRedisRepository;
import com.sebet.order_service.cache.repository.OrderTrackingRedisRepository;
import com.sebet.order_service.cache.repository.VerificationCodeRedisRepository;
import com.sebet.order_service.customer.dto.response.ActiveOrderDetailResponse;
import com.sebet.order_service.customer.dto.response.ActiveOrderItemResponse;
import com.sebet.order_service.customer.dto.response.CancelledOrderDetailResponse;
import com.sebet.order_service.customer.dto.response.OrderHistoryItemResponse;
import com.sebet.order_service.customer.dto.response.OrderProposedChangesResponse;
import com.sebet.order_service.customer.dto.response.OrderStatusResponse;
import com.sebet.order_service.customer.dto.response.OrderTrackingResponse;
import com.sebet.order_service.customer.dto.response.ScheduledOrderDetailResponse;
import com.sebet.order_service.customer.dto.response.VerificationCodeResponse;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderItemEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.repository.OrderItemRepository;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderCancelledBy;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ProductUnit;
import com.sebet.order_service.shared.enums.ScheduleType;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerOrderQueryServiceTest {

    private final OrderRedisRepository orderRedisRepository = mock(OrderRedisRepository.class);
    private final OrderStatusRedisRepository orderStatusRedisRepository = mock(OrderStatusRedisRepository.class);
    private final OrderTimelineRedisRepository orderTimelineRedisRepository = mock(OrderTimelineRedisRepository.class);
    private final OrderTrackingRedisRepository orderTrackingRedisRepository = mock(OrderTrackingRedisRepository.class);
    private final ActiveOrdersRedisRepository activeOrdersRedisRepository = mock(ActiveOrdersRedisRepository.class);
    private final VerificationCodeRedisRepository verificationCodeRedisRepository = mock(VerificationCodeRedisRepository.class);
    private final OrderProposalsRedisRepository orderProposalsRedisRepository = mock(OrderProposalsRedisRepository.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
    private final OrderStatusHistoryRepository orderStatusHistoryRepository = mock(OrderStatusHistoryRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final CustomerOrderQueryService service = new CustomerOrderQueryService(
            orderRedisRepository,
            orderStatusRedisRepository,
            orderTimelineRedisRepository,
            orderTrackingRedisRepository,
            activeOrdersRedisRepository,
            verificationCodeRedisRepository,
            orderProposalsRedisRepository,
            orderRepository,
            orderItemRepository,
            orderStatusHistoryRepository,
            objectMapper
    );

    // ── getOrderHistory ───────────────────────────────────────────────────────

    @Test
    void getOrderHistory_returnsPageWithCorrectRoutes() {
        UUID deliveredId = UUID.randomUUID();
        UUID cancelledId = UUID.randomUUID();
        UUID scheduledId = UUID.randomUUID();

        List<OrderEntity> orders = List.of(
                entity(deliveredId, "user-1", OrderStatus.DELIVERED),
                entity(cancelledId, "user-1", OrderStatus.CANCELLED),
                entity(scheduledId, "user-1", OrderStatus.SCHEDULED)
        );
        Page<OrderEntity> page = new PageImpl<>(orders);
        when(orderRepository.findByCustomerIdAndStatusIn(any(), any(), any())).thenReturn(page);
        when(orderItemRepository.findByOrderIdInOrderByOrderIdAscLineNumberAsc(any())).thenReturn(List.of());

        Page<OrderHistoryItemResponse> result = service.getOrderHistory("user-1", PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(3);
        assertThat(result.getContent().get(0).route()).isEqualTo(OrderHistoryItemResponse.OrderDetailRoute.DELIVERED);
        assertThat(result.getContent().get(1).route()).isEqualTo(OrderHistoryItemResponse.OrderDetailRoute.CANCELLED);
        assertThat(result.getContent().get(2).route()).isEqualTo(OrderHistoryItemResponse.OrderDetailRoute.SCHEDULED);
    }

    @Test
    void getOrderHistory_cancelledOrderHasRefundInfoAndScheduledHasScheduledFor() {
        UUID cancelledId = UUID.randomUUID();
        UUID scheduledId = UUID.randomUUID();
        OffsetDateTime scheduledFor = OffsetDateTime.now().plusDays(1);

        OrderEntity cancelled = entity(cancelledId, "user-1", OrderStatus.CANCELLED);
        OrderEntity scheduled = entity(scheduledId, "user-1", OrderStatus.SCHEDULED);
        scheduled.setScheduledFor(scheduledFor);

        when(orderRepository.findByCustomerIdAndStatusIn(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(cancelled, scheduled)));
        when(orderItemRepository.findByOrderIdInOrderByOrderIdAscLineNumberAsc(any())).thenReturn(List.of());

        Page<OrderHistoryItemResponse> result = service.getOrderHistory("user-1", PageRequest.of(0, 20));

        OrderHistoryItemResponse cancelledRow = result.getContent().get(0);
        assertThat(cancelledRow.refund()).isNotNull();
        assertThat(cancelledRow.scheduledFor()).isNull();

        OrderHistoryItemResponse scheduledRow = result.getContent().get(1);
        assertThat(scheduledRow.refund()).isNull();
        assertThat(scheduledRow.scheduledFor()).isEqualTo(scheduledFor.toString());
    }

    @Test
    void getOrderHistory_usesOneBatchQueryInsteadOfOnePerRow() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        OrderItemEntity itemForId1 = itemForOrder(id1);
        OrderItemEntity itemForId2 = itemForOrder(id2);

        when(orderRepository.findByCustomerIdAndStatusIn(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(
                        entity(id1, "user-1", OrderStatus.DELIVERED),
                        entity(id2, "user-1", OrderStatus.CANCELLED),
                        entity(id3, "user-1", OrderStatus.SCHEDULED)
                )));
        when(orderItemRepository.findByOrderIdInOrderByOrderIdAscLineNumberAsc(any()))
                .thenReturn(List.of(itemForId1, itemForId2));

        Page<OrderHistoryItemResponse> result = service.getOrderHistory("user-1", PageRequest.of(0, 20));

        verify(orderItemRepository, times(1)).findByOrderIdInOrderByOrderIdAscLineNumberAsc(any());
        verify(orderItemRepository, never()).findByOrderIdOrderByLineNumberAsc(any());
        assertThat(result.getContent().get(0).itemCount()).isEqualTo(1);
        assertThat(result.getContent().get(1).itemCount()).isEqualTo(1);
        assertThat(result.getContent().get(2).itemCount()).isEqualTo(0);
    }

    // ── getActiveOrders ───────────────────────────────────────────────────────

    @Test
    void getActiveOrders_returnsCachedOrdersMappedToItems() {
        String orderId = UUID.randomUUID().toString();
        when(activeOrdersRedisRepository.getAll("user-1")).thenReturn(Set.of(orderId));
        when(orderRedisRepository.findById(orderId)).thenReturn(Optional.of(snapshot(orderId, "user-1")));

        List<ActiveOrderItemResponse> result = service.getActiveOrders("user-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).orderId()).isEqualTo(orderId);
        assertThat(result.get(0).storeId()).isEqualTo("store-1");
        assertThat(result.get(0).itemCount()).isEqualTo(1);
    }

    @Test
    void getActiveOrders_skipsMissingC2Entries() {
        String orderId = UUID.randomUUID().toString();
        when(activeOrdersRedisRepository.getAll("user-1")).thenReturn(Set.of(orderId));
        when(orderRedisRepository.findById(orderId)).thenReturn(Optional.empty());

        List<ActiveOrderItemResponse> result = service.getActiveOrders("user-1");

        assertThat(result).isEmpty();
    }

    @Test
    void getActiveOrders_returnsEmptyListWhenNoActiveOrders() {
        when(activeOrdersRedisRepository.getAll("user-1")).thenReturn(Set.of());

        List<ActiveOrderItemResponse> result = service.getActiveOrders("user-1");

        assertThat(result).isEmpty();
        verify(orderRedisRepository, never()).findById(any());
    }

    // ── getActiveOrderDetail ──────────────────────────────────────────────────

    @Test
    void getActiveOrderDetail_happyPathReturnsMappedDetail() {
        String orderId = UUID.randomUUID().toString();
        RedisOrder snap = snapshot(orderId, "user-1");
        when(orderRedisRepository.findById(orderId)).thenReturn(Optional.of(snap));
        when(orderStatusRedisRepository.findById(orderId)).thenReturn(Optional.of(new OrderStatusRedisRepository.Entry("PENDING", "user-1", "store-1")));
        when(orderTimelineRedisRepository.findAll(orderId)).thenReturn(List.of(
                new OrderTimelineEntry("PLACED", "2026-07-06T10:00:00Z")
        ));

        ActiveOrderDetailResponse result = service.getActiveOrderDetail("user-1", orderId);

        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(result.storeId()).isEqualTo("store-1");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).productId()).isEqualTo("product-1");
        assertThat(result.timeline()).hasSize(4);
        assertThat(result.timeline().get(0).status()).isEqualTo("PLACED");
        assertThat(result.timeline().get(0).occurredAt()).isEqualTo("2026-07-06T10:00:00Z");
        assertThat(result.timeline().get(1).occurredAt()).isNull();
        assertThat(result.verificationCode()).isNull();
    }

    @Test
    void getActiveOrderDetail_throwsNotFoundWhenC2Missing() {
        String orderId = UUID.randomUUID().toString();
        when(orderRedisRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getActiveOrderDetail("user-1", orderId))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void getActiveOrderDetail_throwsNotFoundWhenWrongUserId() {
        String orderId = UUID.randomUUID().toString();
        when(orderRedisRepository.findById(orderId))
                .thenReturn(Optional.of(snapshot(orderId, "other-user")));

        assertThatThrownBy(() -> service.getActiveOrderDetail("user-1", orderId))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void getActiveOrderDetail_includesVerificationCodeWhenStatusIsArrived() {
        String orderId = UUID.randomUUID().toString();
        when(orderRedisRepository.findById(orderId)).thenReturn(Optional.of(snapshot(orderId, "user-1")));
        when(orderStatusRedisRepository.findById(orderId)).thenReturn(Optional.of(new OrderStatusRedisRepository.Entry("ARRIVED", "user-1", "store-1")));
        when(orderTimelineRedisRepository.findAll(orderId)).thenReturn(List.of());
        when(verificationCodeRedisRepository.findById(orderId))
                .thenReturn(Optional.of(new VerificationCodeCacheDto("47", "2026-07-06T12:00:00Z", null)));

        ActiveOrderDetailResponse result = service.getActiveOrderDetail("user-1", orderId);

        assertThat(result.verificationCode()).isEqualTo("47");
    }

    @Test
    void getActiveOrderDetail_doesNotFetchVerificationCodeForNonArrivedStatus() {
        String orderId = UUID.randomUUID().toString();
        when(orderRedisRepository.findById(orderId)).thenReturn(Optional.of(snapshot(orderId, "user-1")));
        when(orderStatusRedisRepository.findById(orderId)).thenReturn(Optional.of(new OrderStatusRedisRepository.Entry("OUT_FOR_DELIVERY", "user-1", "store-1")));
        when(orderTimelineRedisRepository.findAll(orderId)).thenReturn(List.of());

        service.getActiveOrderDetail("user-1", orderId);

        verify(verificationCodeRedisRepository, never()).findById(any());
    }

    @Test
    void getActiveOrderDetail_fallsBackToDbWhenCachedStatusIsInvalid() {
        UUID id = UUID.randomUUID();
        String orderId = id.toString();
        when(orderRedisRepository.findById(orderId)).thenReturn(Optional.of(snapshot(orderId, "user-1")));
        when(orderStatusRedisRepository.findById(orderId))
                .thenReturn(Optional.of(new OrderStatusRedisRepository.Entry("NOT_A_STATUS", "user-1", "store-1")));
        when(orderRepository.findByIdAndCustomerId(id, "user-1"))
                .thenReturn(Optional.of(entity(id, "user-1", OrderStatus.READY_FOR_PICKUP)));
        when(orderTimelineRedisRepository.findAll(orderId)).thenReturn(List.of());

        ActiveOrderDetailResponse result = service.getActiveOrderDetail("user-1", orderId);

        assertThat(result.status()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
    }

    @Test
    void getActiveOrderDetail_includesDriverWhenAssigned() {
        String orderId = UUID.randomUUID().toString();
        RedisOrder snap = snapshot(orderId, "user-1");
        snap.setDriver(DriverInfo.builder()
                .driverId("driver-1").name("Ahmet K.").phone("+90 *** ** 47")
                .rating(4.9).vehicle("Toyota Corolla").plateNumber("06 AB 123")
                .build());
        when(orderRedisRepository.findById(orderId)).thenReturn(Optional.of(snap));
        when(orderStatusRedisRepository.findById(orderId)).thenReturn(Optional.of(new OrderStatusRedisRepository.Entry("OUT_FOR_DELIVERY", "user-1", "store-1")));
        when(orderTimelineRedisRepository.findAll(orderId)).thenReturn(List.of());

        ActiveOrderDetailResponse result = service.getActiveOrderDetail("user-1", orderId);

        assertThat(result.driver()).isNotNull();
        assertThat(result.driver().driverId()).isEqualTo("driver-1");
        assertThat(result.driver().name()).isEqualTo("Ahmet K.");
    }

    // ── getScheduledOrderDetail ───────────────────────────────────────────────

    @Test
    void getScheduledOrderDetail_happyPathCanCancelTrue() {
        UUID id = UUID.randomUUID();
        OrderEntity order = entity(id, "user-1", OrderStatus.SCHEDULED);
        order.setScheduledFor(OffsetDateTime.now().plusHours(3));
        when(orderRepository.findByIdAndCustomerId(id, "user-1")).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(id)).thenReturn(List.of(item()));

        ScheduledOrderDetailResponse result = service.getScheduledOrderDetail("user-1", id.toString());

        assertThat(result.orderId()).isEqualTo(id.toString());
        assertThat(result.canCancel()).isTrue();
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void getScheduledOrderDetail_canCancelFalseWhenWithinOneHour() {
        UUID id = UUID.randomUUID();
        OrderEntity order = entity(id, "user-1", OrderStatus.SCHEDULED);
        order.setScheduledFor(OffsetDateTime.now().plusMinutes(30));
        when(orderRepository.findByIdAndCustomerId(id, "user-1")).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(id)).thenReturn(List.of());

        ScheduledOrderDetailResponse result = service.getScheduledOrderDetail("user-1", id.toString());

        assertThat(result.canCancel()).isFalse();
    }

    @Test
    void getScheduledOrderDetail_throwsNotFoundWhenStatusIsNotScheduled() {
        UUID id = UUID.randomUUID();
        OrderEntity order = entity(id, "user-1", OrderStatus.PENDING);
        when(orderRepository.findByIdAndCustomerId(id, "user-1")).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.getScheduledOrderDetail("user-1", id.toString()))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // ── getCancelledOrderDetail ───────────────────────────────────────────────

    @Test
    void getCancelledOrderDetail_happyPath() {
        UUID id = UUID.randomUUID();
        OrderEntity order = entity(id, "user-1", OrderStatus.CANCELLED);
        order.setCancelledBy(OrderCancelledBy.USER);
        order.setCancelledAt(OffsetDateTime.parse("2026-07-06T11:00:00Z"));
        when(orderRepository.findByIdAndCustomerId(id, "user-1")).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(id)).thenReturn(List.of(item()));

        CancelledOrderDetailResponse result = service.getCancelledOrderDetail("user-1", id.toString());

        assertThat(result.orderId()).isEqualTo(id.toString());
        assertThat(result.cancelledBy()).isEqualTo(OrderCancelledBy.USER);
        assertThat(result.refund()).isNotNull();
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void getCancelledOrderDetail_throwsNotFoundWhenStatusIsNotCancelled() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findByIdAndCustomerId(id, "user-1"))
                .thenReturn(Optional.of(entity(id, "user-1", OrderStatus.PENDING)));

        assertThatThrownBy(() -> service.getCancelledOrderDetail("user-1", id.toString()))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // ── routeOrderDetail ──────────────────────────────────────────────────────

    @Test
    void routeOrderDetail_deliveredOrderReturnsDeliveredResult() {
        UUID id = UUID.randomUUID();
        OrderEntity order = entity(id, "user-1", OrderStatus.DELIVERED);
        order.setDeliveredAt(OffsetDateTime.parse("2026-07-06T12:30:00Z"));
        when(orderRepository.findByIdAndCustomerId(id, "user-1")).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(id)).thenReturn(List.of());
        when(orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(id)).thenReturn(List.of());

        CustomerOrderRouterResult result = service.routeOrderDetail("user-1", id.toString());

        assertThat(result).isInstanceOf(CustomerOrderRouterResult.Delivered.class);
        CustomerOrderRouterResult.Delivered delivered = (CustomerOrderRouterResult.Delivered) result;
        assertThat(delivered.response().orderId()).isEqualTo(id.toString());
        assertThat(delivered.response().deliveredAt()).isEqualTo("2026-07-06T12:30Z");
    }

    @Test
    void routeOrderDetail_cancelledOrderReturnsRedirectToCancelled() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findByIdAndCustomerId(id, "user-1"))
                .thenReturn(Optional.of(entity(id, "user-1", OrderStatus.CANCELLED)));

        CustomerOrderRouterResult result = service.routeOrderDetail("user-1", id.toString());

        assertThat(result).isInstanceOf(CustomerOrderRouterResult.Redirect.class);
        assertThat(((CustomerOrderRouterResult.Redirect) result).location())
                .isEqualTo("/api/v1/orders/cancelled/" + id);
    }

    @Test
    void routeOrderDetail_scheduledOrderReturnsRedirectToScheduled() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findByIdAndCustomerId(id, "user-1"))
                .thenReturn(Optional.of(entity(id, "user-1", OrderStatus.SCHEDULED)));

        CustomerOrderRouterResult result = service.routeOrderDetail("user-1", id.toString());

        assertThat(result).isInstanceOf(CustomerOrderRouterResult.Redirect.class);
        assertThat(((CustomerOrderRouterResult.Redirect) result).location())
                .isEqualTo("/api/v1/orders/scheduled/" + id);
    }

    @Test
    void routeOrderDetail_pendingOrderReturnsRedirectToActive() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findByIdAndCustomerId(id, "user-1"))
                .thenReturn(Optional.of(entity(id, "user-1", OrderStatus.PENDING)));

        CustomerOrderRouterResult result = service.routeOrderDetail("user-1", id.toString());

        assertThat(result).isInstanceOf(CustomerOrderRouterResult.Redirect.class);
        assertThat(((CustomerOrderRouterResult.Redirect) result).location())
                .isEqualTo("/api/v1/orders/active/" + id);
    }

    @Test
    void routeOrderDetail_throwsNotFoundWhenOrderMissing() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findByIdAndCustomerId(id, "user-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.routeOrderDetail("user-1", id.toString()))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // ── getOrderStatus ────────────────────────────────────────────────────────

    @Test
    void getOrderStatus_returnsStatusFromC4WhenPresent() {
        String orderId = UUID.randomUUID().toString();
        when(orderStatusRedisRepository.findById(orderId)).thenReturn(Optional.of(new OrderStatusRedisRepository.Entry("CONFIRMED", "user-1", "store-1")));

        OrderStatusResponse result = service.getOrderStatus("user-1", orderId);

        assertThat(result.status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(orderRepository, never()).findByIdAndCustomerId(any(), any());
        verify(orderRedisRepository, never()).findById(any());
    }

    @Test
    void getOrderStatus_fallsBackToDbWhenC4Missing() {
        UUID id = UUID.randomUUID();
        String orderId = id.toString();
        when(orderStatusRedisRepository.findById(orderId)).thenReturn(Optional.empty());
        when(orderRepository.findByIdAndCustomerId(id, "user-1"))
                .thenReturn(Optional.of(entity(id, "user-1", OrderStatus.OUT_FOR_DELIVERY)));

        OrderStatusResponse result = service.getOrderStatus("user-1", orderId);

        assertThat(result.status()).isEqualTo(OrderStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void getOrderStatus_throwsNotFoundWhenC4ShowsWrongOwner() {
        String orderId = UUID.randomUUID().toString();
        when(orderStatusRedisRepository.findById(orderId)).thenReturn(Optional.of(new OrderStatusRedisRepository.Entry("PENDING", "other-user", "store-1")));

        assertThatThrownBy(() -> service.getOrderStatus("user-1", orderId))
                .isInstanceOf(OrderNotFoundException.class);
        verify(orderRedisRepository, never()).findById(any());
    }

    // ── getOrderTracking ──────────────────────────────────────────────────────

    @Test
    void getOrderStatus_fallsBackToDbWhenCachedStatusIsInvalid() {
        UUID id = UUID.randomUUID();
        String orderId = id.toString();
        when(orderStatusRedisRepository.findById(orderId))
                .thenReturn(Optional.of(new OrderStatusRedisRepository.Entry("NOT_A_STATUS", "user-1", "store-1")));
        when(orderRepository.findByIdAndCustomerId(id, "user-1"))
                .thenReturn(Optional.of(entity(id, "user-1", OrderStatus.CONFIRMED)));

        OrderStatusResponse result = service.getOrderStatus("user-1", orderId);

        assertThat(result.status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(orderRedisRepository, never()).findById(any());
    }

    @Test
    void getOrderTracking_returnsLiveGpsWhenC3Present() {
        String orderId = UUID.randomUUID().toString();
        when(orderRedisRepository.findById(orderId))
                .thenReturn(Optional.of(snapshot(orderId, "user-1")));
        when(orderStatusRedisRepository.findById(orderId)).thenReturn(Optional.of(new OrderStatusRedisRepository.Entry("OUT_FOR_DELIVERY", "user-1", "store-1")));
        when(orderTrackingRedisRepository.findById(orderId)).thenReturn(Optional.of(
                RedisOrderTracking.builder()
                        .etaMinutes(8).driverLat(41.315).driverLng(69.283)
                        .updatedAt("2026-07-06T12:00:05Z").build()
        ));

        OrderTrackingResponse result = service.getOrderTracking("user-1", orderId);

        assertThat(result.status()).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(result.etaMinutes()).isEqualTo(8);
        assertThat(result.driverLat()).isEqualTo(41.315);
        assertThat(result.driverLng()).isEqualTo(69.283);
        assertThat(result.updatedAt()).isEqualTo("2026-07-06T12:00:05Z");
    }

    @Test
    void getOrderTracking_returnsNullGpsWhenC3Absent() {
        String orderId = UUID.randomUUID().toString();
        when(orderRedisRepository.findById(orderId))
                .thenReturn(Optional.of(snapshot(orderId, "user-1")));
        when(orderStatusRedisRepository.findById(orderId)).thenReturn(Optional.of(new OrderStatusRedisRepository.Entry("CONFIRMED", "user-1", "store-1")));
        when(orderTrackingRedisRepository.findById(orderId)).thenReturn(Optional.empty());

        OrderTrackingResponse result = service.getOrderTracking("user-1", orderId);

        assertThat(result.etaMinutes()).isNull();
        assertThat(result.driverLat()).isNull();
        assertThat(result.driverLng()).isNull();
        assertThat(result.updatedAt()).isNull();
    }

    // ── getVerificationCode ───────────────────────────────────────────────────

    @Test
    void getVerificationCode_returnsCodeWhenPresent() {
        String orderId = UUID.randomUUID().toString();
        when(orderRedisRepository.findById(orderId))
                .thenReturn(Optional.of(snapshot(orderId, "user-1")));
        when(verificationCodeRedisRepository.findById(orderId))
                .thenReturn(Optional.of(new VerificationCodeCacheDto("04", "2026-07-06T12:00:00Z", null)));

        VerificationCodeResponse result = service.getVerificationCode("user-1", orderId);

        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.code()).isEqualTo("04");
    }

    @Test
    void getVerificationCode_throwsNotFoundWhenC7Absent() {
        String orderId = UUID.randomUUID().toString();
        when(orderRedisRepository.findById(orderId))
                .thenReturn(Optional.of(snapshot(orderId, "user-1")));
        when(verificationCodeRedisRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getVerificationCode("user-1", orderId))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // ── getProposedChanges ────────────────────────────────────────────────────

    @Test
    void getProposedChanges_returnsMappedProposalsWhenPresent() {
        String orderId = UUID.randomUUID().toString();
        when(orderRedisRepository.findById(orderId))
                .thenReturn(Optional.of(snapshot(orderId, "user-1")));
        when(orderProposalsRedisRepository.find(orderId)).thenReturn(Optional.of(
                OrderProposalsCacheDto.builder()
                        .orderId(orderId)
                        .proposedAt("2026-07-06T11:30:00Z")
                        .items(List.of(OrderProposalsCacheDto.ProposedItem.builder()
                                .productId("product-1")
                                .productName("Apples")
                                .requestedQuantity(new BigDecimal("2.000"))
                                .unit(ProductUnit.KG)
                                .availableQuantity(new BigDecimal("1.000"))
                                .build()))
                        .build()
        ));

        OrderProposedChangesResponse result = service.getProposedChanges("user-1", orderId);

        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.proposedAt()).isEqualTo("2026-07-06T11:30:00Z");
        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).productId()).isEqualTo("product-1");
        assertThat(result.changes().get(0).availableQuantity()).isEqualByComparingTo("1.000");
    }

    @Test
    void getProposedChanges_throwsNotFoundWhenC8Absent() {
        String orderId = UUID.randomUUID().toString();
        when(orderRedisRepository.findById(orderId))
                .thenReturn(Optional.of(snapshot(orderId, "user-1")));
        when(orderProposalsRedisRepository.find(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProposedChanges("user-1", orderId))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // ── Timeline building ─────────────────────────────────────────────────────

    @Test
    void getActiveOrderDetail_timelineAlwaysHasFourStepsWithNullsForFutureSteps() {
        String orderId = UUID.randomUUID().toString();
        when(orderRedisRepository.findById(orderId)).thenReturn(Optional.of(snapshot(orderId, "user-1")));
        when(orderStatusRedisRepository.findById(orderId)).thenReturn(Optional.of(new OrderStatusRedisRepository.Entry("CONFIRMED", "user-1", "store-1")));
        when(orderTimelineRedisRepository.findAll(orderId)).thenReturn(List.of(
                new OrderTimelineEntry("PLACED", "2026-07-06T10:00:00Z"),
                new OrderTimelineEntry("PACKED", "2026-07-06T10:09:00Z")
        ));

        ActiveOrderDetailResponse result = service.getActiveOrderDetail("user-1", orderId);

        assertThat(result.timeline()).hasSize(4);
        assertThat(result.timeline().get(0).status()).isEqualTo("PLACED");
        assertThat(result.timeline().get(0).occurredAt()).isEqualTo("2026-07-06T10:00:00Z");
        assertThat(result.timeline().get(1).status()).isEqualTo("PACKED");
        assertThat(result.timeline().get(1).occurredAt()).isEqualTo("2026-07-06T10:09:00Z");
        assertThat(result.timeline().get(2).status()).isEqualTo("ON_THE_WAY");
        assertThat(result.timeline().get(2).occurredAt()).isNull();
        assertThat(result.timeline().get(3).status()).isEqualTo("ARRIVED");
        assertThat(result.timeline().get(3).occurredAt()).isNull();
    }

    @Test
    void routeOrderDetail_deliveredTimelineBuiltFromStatusHistory() {
        UUID id = UUID.randomUUID();
        OffsetDateTime base = OffsetDateTime.parse("2026-07-06T10:00:00Z");
        OrderEntity order = entity(id, "user-1", OrderStatus.DELIVERED);
        order.setDeliveredAt(base.plusMinutes(30));
        when(orderRepository.findByIdAndCustomerId(id, "user-1")).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderIdOrderByLineNumberAsc(id)).thenReturn(List.of());
        when(orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(id)).thenReturn(List.of(
                historyEntry(id, null, OrderStatus.PENDING, base),
                historyEntry(id, OrderStatus.PENDING, OrderStatus.CONFIRMED, base.plusMinutes(9)),
                historyEntry(id, OrderStatus.CONFIRMED, OrderStatus.OUT_FOR_DELIVERY, base.plusMinutes(15)),
                historyEntry(id, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED, base.plusMinutes(30))
        ));

        CustomerOrderRouterResult result = service.routeOrderDetail("user-1", id.toString());

        CustomerOrderRouterResult.Delivered delivered = (CustomerOrderRouterResult.Delivered) result;
        assertThat(delivered.response().timeline()).hasSize(4);
        assertThat(delivered.response().timeline().get(0).status()).isEqualTo("PLACED");
        assertThat(delivered.response().timeline().get(0).occurredAt()).isNotNull();
        assertThat(delivered.response().timeline().get(1).status()).isEqualTo("PACKED");
        assertThat(delivered.response().timeline().get(2).status()).isEqualTo("ON_THE_WAY");
        assertThat(delivered.response().timeline().get(3).status()).isEqualTo("ARRIVED");
        assertThat(delivered.response().timeline().get(3).occurredAt()).isNotNull();
    }

    // ── orderNumber format ────────────────────────────────────────────────────

    @Test
    void getActiveOrderDetail_orderNumberFormattedFromLast8UuidHexChars() {
        // UUID ending in 55440000 → #55440000
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String orderId = id.toString();
        when(orderRedisRepository.findById(orderId)).thenReturn(Optional.of(snapshot(orderId, "user-1")));
        when(orderStatusRedisRepository.findById(orderId)).thenReturn(Optional.of(new OrderStatusRedisRepository.Entry("PENDING", "user-1", "store-1")));
        when(orderTimelineRedisRepository.findAll(orderId)).thenReturn(List.of());

        ActiveOrderDetailResponse result = service.getActiveOrderDetail("user-1", orderId);

        assertThat(result.orderNumber()).isEqualTo("#55440000");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RedisOrder snapshot(String orderId, String userId) {
        return RedisOrder.builder()
                .orderId(orderId)
                .userId(userId)
                .storeId("store-1")
                .storeName("Test Store")
                .totalAmount(new BigDecimal("36000.00"))
                .deliveryAddress(DeliveryAddress.builder()
                        .street("Amir Temur 25").city("Tashkent")
                        .lat(41.3111).lng(69.2797).build())
                .storeLocation(StoreLocation.builder().lat(41.3201).lng(69.2405).build())
                .items(List.of(OrderItem.builder()
                        .productId("product-1").name("Apples").imageUrl("https://cdn/apple.png")
                        .quantity(2).unitPrice(new BigDecimal("12000")).subtotal(new BigDecimal("24000"))
                        .build()))
                .createdAt("2026-07-06T10:00:00Z")
                .build();
    }

    private OrderEntity entity(UUID id, String userId, OrderStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        OrderEntity e = new OrderEntity();
        e.setId(id);
        e.setCustomerId(userId);
        e.setStoreId("store-1");
        e.setCartId("cart-1");
        e.setStatus(status);
        e.setScheduleType(ScheduleType.IMMEDIATE);
        e.setSubtotalAmount(new BigDecimal("33000.00"));
        e.setItemDiscountAmount(BigDecimal.ZERO);
        e.setOrderDiscountAmount(BigDecimal.ZERO);
        e.setDeliveryFeeAmount(new BigDecimal("3000.00"));
        e.setTotalAmount(new BigDecimal("36000.00"));
        e.setCurrency("UZS");
        e.setDeliveryAddressJson("{\"street\":\"Amir Temur 25\",\"city\":\"Tashkent\"}");
        e.setDeliveryLat(new BigDecimal("41.311100"));
        e.setDeliveryLng(new BigDecimal("69.279700"));
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }

    private OrderItemEntity item() {
        return itemForOrder(UUID.randomUUID());
    }

    private OrderItemEntity itemForOrder(UUID orderId) {
        OrderItemEntity i = new OrderItemEntity();
        i.setId(UUID.randomUUID());
        i.setOrderId(orderId);
        i.setLineNumber(1);
        i.setProductId("product-1");
        i.setProductName("Apples");
        i.setQuantity(new BigDecimal("2.000"));
        i.setUnit(ProductUnit.KG);
        i.setUnitPriceAmount(new BigDecimal("12000.00"));
        i.setGrossAmount(new BigDecimal("24000.00"));
        i.setDiscountAmount(BigDecimal.ZERO);
        i.setNetAmount(new BigDecimal("22000.00"));
        i.setImageUrl("https://cdn/apple.png");
        i.setCreatedAt(OffsetDateTime.now());
        return i;
    }

    private OrderStatusHistoryEntity historyEntry(UUID orderId, OrderStatus from, OrderStatus to, OffsetDateTime at) {
        OrderStatusHistoryEntity h = new OrderStatusHistoryEntity();
        h.setId(UUID.randomUUID());
        h.setOrderId(orderId);
        h.setFromStatus(from);
        h.setToStatus(to);
        h.setChangedByType("SYSTEM");
        h.setCreatedAt(at);
        return h;
    }
}
