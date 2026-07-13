package com.sebet.order_service.customer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.DeliveryAddress;
import com.sebet.order_service.cache.service.OrderRespondAcceptRedisUpdater;
import com.sebet.order_service.cache.service.OrderScheduledUpdateRedisWriter;
import com.sebet.order_service.customer.dto.request.UpdateScheduledOrderRequest;
import com.sebet.order_service.customer.dto.response.ActivateScheduledNowResponse;
import com.sebet.order_service.customer.dto.response.ScheduledOrderDetailResponse;
import com.sebet.order_service.integration.store.StoreServiceClient;
import com.sebet.order_service.integration.store.dto.StoreWorkingHoursResponse;
import com.sebet.order_service.order.service.OrderLifecycleResult;
import com.sebet.order_service.order.service.OrderLifecycleService;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ScheduleType;
import com.sebet.order_service.shared.exception.InvalidScheduledWindowException;
import com.sebet.order_service.shared.exception.OrderInvalidTransitionException;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
import com.sebet.order_service.shared.exception.ScheduledOrderModificationWindowClosedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerOrderLifecycleServiceTest {

    private final OrderLifecycleService orderLifecycleService = mock(OrderLifecycleService.class);
    private final OrderRespondAcceptRedisUpdater respondAcceptRedisUpdater = mock(OrderRespondAcceptRedisUpdater.class);
    private final OrderScheduledUpdateRedisWriter scheduledUpdateRedisWriter = mock(OrderScheduledUpdateRedisWriter.class);
    private final StoreServiceClient storeServiceClient = mock(StoreServiceClient.class);
    private final CustomerOrderQueryService queryService = mock(CustomerOrderQueryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final CustomerOrderLifecycleService service = new CustomerOrderLifecycleService(
            orderLifecycleService,
            respondAcceptRedisUpdater,
            scheduledUpdateRedisWriter,
            storeServiceClient,
            queryService,
            objectMapper
    );

    @BeforeEach
    void setUpValueFields() {
        ReflectionTestUtils.setField(service, "modificationCutoffMinutes", 40);
        ReflectionTestUtils.setField(service, "minLeadTimeMinutes", 60);
        ReflectionTestUtils.setField(service, "slotIntervalMinutes", 15);
        ReflectionTestUtils.setField(service, "deliveryWindowDurationMinutes", 30);
    }

    // ── activateNow ───────────────────────────────────────────────────────────

    @Test
    void activateNow_scheduledOrder_returnsPendingStatus() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = scheduledOrder(orderId, OffsetDateTime.now().plusHours(3));
        OffsetDateTime changedAt = OffsetDateTime.parse("2026-07-20T10:00:00Z");

        when(orderLifecycleService.customerActivateScheduled(orderId.toString(), "user-1"))
                .thenReturn(new OrderLifecycleResult(order, OrderStatus.SCHEDULED, OrderStatus.PENDING, changedAt));

        ActivateScheduledNowResponse response = service.activateNow("user-1", orderId.toString());

        assertThat(response.orderId()).isEqualTo(orderId.toString());
        assertThat(response.newStatus()).isEqualTo("PENDING");
        assertThat(response.changedAt()).isEqualTo(changedAt.toString());

        verify(orderLifecycleService).updateScheduledActivationRedisViews(
                eq(order), eq(changedAt), eq("CUSTOMER_ACTIVATE_NOW_" + orderId));
    }

    @Test
    void activateNow_wrongStatus_throwsOrderInvalidTransition() {
        UUID orderId = UUID.randomUUID();
        when(orderLifecycleService.customerActivateScheduled(orderId.toString(), "user-1"))
                .thenThrow(new OrderInvalidTransitionException(orderId.toString(), OrderStatus.PENDING, OrderStatus.PENDING));

        assertThatThrownBy(() -> service.activateNow("user-1", orderId.toString()))
                .isInstanceOf(OrderInvalidTransitionException.class);

        verify(orderLifecycleService, never()).updateScheduledActivationRedisViews(any(), any(), any());
    }

    @Test
    void activateNow_orderNotFound_throwsOrderNotFound() {
        UUID orderId = UUID.randomUUID();
        when(orderLifecycleService.customerActivateScheduled(orderId.toString(), "user-99"))
                .thenThrow(new OrderNotFoundException(orderId.toString()));

        assertThatThrownBy(() -> service.activateNow("user-99", orderId.toString()))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // ── updateScheduledOrder — cross-field guard ──────────────────────────────

    @Test
    void updateScheduledOrder_allNullFields_throwsIllegalArgument() {
        UUID orderId = UUID.randomUUID();
        UpdateScheduledOrderRequest request = new UpdateScheduledOrderRequest(null, null, null);

        assertThatThrownBy(() -> service.updateScheduledOrder("user-1", orderId.toString(), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one");

        verify(orderLifecycleService, never()).loadScheduledOrderForCustomer(any(), any());
    }

    // ── updateScheduledOrder — modification window ────────────────────────────

    @Test
    void updateScheduledOrder_modificationWindowClosed_throws409() {
        UUID orderId = UUID.randomUUID();
        // scheduledFor is only 20 minutes away — within the 40-minute cutoff
        OrderEntity order = scheduledOrder(orderId, OffsetDateTime.now().plusMinutes(20));
        when(orderLifecycleService.loadScheduledOrderForCustomer(orderId.toString(), "user-1"))
                .thenReturn(order);

        UpdateScheduledOrderRequest request = new UpdateScheduledOrderRequest(null, null, "+998901234567");

        assertThatThrownBy(() -> service.updateScheduledOrder("user-1", orderId.toString(), request))
                .isInstanceOf(ScheduledOrderModificationWindowClosedException.class);

        verify(orderLifecycleService, never()).customerUpdateScheduled(any(), any(), any(), any(), any(), any(), any());
    }

    // ── updateScheduledOrder — phone-only update (no window validation) ───────

    @Test
    void updateScheduledOrder_phoneNumberOnly_persistsAndReturnsDetail() {
        UUID orderId = UUID.randomUUID();
        OffsetDateTime scheduledFor = OffsetDateTime.now().plusHours(3);
        OrderEntity order = scheduledOrder(orderId, scheduledFor);
        OrderEntity saved = scheduledOrder(orderId, scheduledFor);
        ScheduledOrderDetailResponse detailResponse = minimalDetail(orderId.toString(), scheduledFor);

        when(orderLifecycleService.loadScheduledOrderForCustomer(orderId.toString(), "user-1")).thenReturn(order);
        when(orderLifecycleService.customerUpdateScheduled(
                eq(orderId.toString()), eq("user-1"),
                isNull(), isNull(), isNull(), isNull(), eq("+998901234567")))
                .thenReturn(saved);
        when(queryService.getScheduledOrderDetail("user-1", orderId.toString())).thenReturn(detailResponse);

        ScheduledOrderDetailResponse result = service.updateScheduledOrder(
                "user-1", orderId.toString(),
                new UpdateScheduledOrderRequest(null, null, "+998901234567")
        );

        assertThat(result).isSameAs(detailResponse);
        verify(scheduledUpdateRedisWriter).apply(
                eq("store-1"), eq(orderId.toString()), isNull(),
                isNull(), eq("+998901234567"), anyString());
    }

    // ── updateScheduledOrder — address-only update ────────────────────────────

    @Test
    void updateScheduledOrder_addressOnly_persistsAndReturnsDetail() {
        UUID orderId = UUID.randomUUID();
        OffsetDateTime scheduledFor = OffsetDateTime.now().plusHours(3);
        OrderEntity order = scheduledOrder(orderId, scheduledFor);
        OrderEntity saved = scheduledOrder(orderId, scheduledFor);
        ScheduledOrderDetailResponse detailResponse = minimalDetail(orderId.toString(), scheduledFor);

        UpdateScheduledOrderRequest.NewDeliveryAddress addr = new UpdateScheduledOrderRequest.NewDeliveryAddress(
                "Home", "New Street 10", "Tashkent",
                new BigDecimal("41.320000"), new BigDecimal("69.250000")
        );

        when(orderLifecycleService.loadScheduledOrderForCustomer(orderId.toString(), "user-1")).thenReturn(order);
        when(orderLifecycleService.customerUpdateScheduled(
                eq(orderId.toString()), eq("user-1"),
                isNull(), anyString(), any(BigDecimal.class), any(BigDecimal.class), isNull()))
                .thenReturn(saved);
        when(queryService.getScheduledOrderDetail("user-1", orderId.toString())).thenReturn(detailResponse);

        ScheduledOrderDetailResponse result = service.updateScheduledOrder(
                "user-1", orderId.toString(),
                new UpdateScheduledOrderRequest(null, addr, null)
        );

        assertThat(result).isSameAs(detailResponse);
        verify(scheduledUpdateRedisWriter).apply(
                eq("store-1"), eq(orderId.toString()), isNull(),
                any(DeliveryAddress.class), isNull(), anyString());
    }

    // ── updateScheduledOrder — scheduledWindowStart validation ───────────────

    @Test
    void updateScheduledOrder_validNewSlot_persistsAndReturnsDetail() {
        UUID orderId = UUID.randomUUID();
        // Use fixed mid-morning times tomorrow to avoid store-hours edge cases at runtime
        OffsetDateTime currentScheduledFor = OffsetDateTime.now().plusDays(1)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime newSlot = currentScheduledFor.plusMinutes(15);

        OrderEntity order = scheduledOrder(orderId, currentScheduledFor);
        OrderEntity saved = scheduledOrder(orderId, newSlot);
        ScheduledOrderDetailResponse detailResponse = minimalDetail(orderId.toString(), newSlot);

        when(orderLifecycleService.loadScheduledOrderForCustomer(orderId.toString(), "user-1")).thenReturn(order);
        when(storeServiceClient.getWorkingHours("store-1")).thenReturn(allDayWorkingHours());
        when(orderLifecycleService.customerUpdateScheduled(
                eq(orderId.toString()), eq("user-1"),
                any(OffsetDateTime.class), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(saved);
        when(queryService.getScheduledOrderDetail("user-1", orderId.toString())).thenReturn(detailResponse);

        ScheduledOrderDetailResponse result = service.updateScheduledOrder(
                "user-1", orderId.toString(),
                new UpdateScheduledOrderRequest(newSlot.toString(), null, null)
        );

        assertThat(result).isSameAs(detailResponse);
        verify(scheduledUpdateRedisWriter).apply(
                eq("store-1"), eq(orderId.toString()), any(),
                isNull(), isNull(), anyString());
    }

    @Test
    void updateScheduledOrder_invalidIso8601_throwsInvalidScheduledWindow() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = scheduledOrder(orderId, OffsetDateTime.now().plusHours(3));
        when(orderLifecycleService.loadScheduledOrderForCustomer(orderId.toString(), "user-1")).thenReturn(order);

        assertThatThrownBy(() -> service.updateScheduledOrder(
                "user-1", orderId.toString(),
                new UpdateScheduledOrderRequest("not-a-date", null, null)
        )).isInstanceOf(InvalidScheduledWindowException.class)
                .hasMessageContaining("not a valid ISO-8601");

        verify(orderLifecycleService, never()).customerUpdateScheduled(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateScheduledOrder_tooSoon_throwsInvalidScheduledWindow() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = scheduledOrder(orderId, OffsetDateTime.now().plusHours(3));
        when(orderLifecycleService.loadScheduledOrderForCustomer(orderId.toString(), "user-1")).thenReturn(order);

        // Only 30 minutes in the future — below 60-minute minLeadTime, forced to a 15-min slot boundary
        OffsetDateTime base = OffsetDateTime.now().plusMinutes(30).withSecond(0).withNano(0);
        int minute = base.getMinute();
        OffsetDateTime tooSoon = base.withMinute(minute - (minute % 15));

        assertThatThrownBy(() -> service.updateScheduledOrder(
                "user-1", orderId.toString(),
                new UpdateScheduledOrderRequest(tooSoon.toString(), null, null)
        )).isInstanceOf(InvalidScheduledWindowException.class)
                .hasMessageContaining("at least");

        verify(orderLifecycleService, never()).customerUpdateScheduled(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateScheduledOrder_misalignedSlot_throwsInvalidScheduledWindow() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = scheduledOrder(orderId, OffsetDateTime.now().plusHours(3));
        when(orderLifecycleService.loadScheduledOrderForCustomer(orderId.toString(), "user-1")).thenReturn(order);

        // 14:32 does not align to 15-min slot
        OffsetDateTime badSlot = OffsetDateTime.now().plusHours(5).withMinute(32).withSecond(0).withNano(0);

        assertThatThrownBy(() -> service.updateScheduledOrder(
                "user-1", orderId.toString(),
                new UpdateScheduledOrderRequest(badSlot.toString(), null, null)
        )).isInstanceOf(InvalidScheduledWindowException.class)
                .hasMessageContaining("align");

        verify(orderLifecycleService, never()).customerUpdateScheduled(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateScheduledOrder_diffSmallerThanSlotInterval_throwsInvalidScheduledWindow() {
        UUID orderId = UUID.randomUUID();
        // currentScheduledFor at exactly some aligned slot
        OffsetDateTime currentScheduledFor = OffsetDateTime.now().plusHours(3).withMinute(0).withSecond(0).withNano(0);
        OrderEntity order = scheduledOrder(orderId, currentScheduledFor);
        when(orderLifecycleService.loadScheduledOrderForCustomer(orderId.toString(), "user-1")).thenReturn(order);

        // New slot only 5 minutes away from current — less than 15-minute slotInterval
        OffsetDateTime tooClose = currentScheduledFor.plusMinutes(5);

        assertThatThrownBy(() -> service.updateScheduledOrder(
                "user-1", orderId.toString(),
                new UpdateScheduledOrderRequest(tooClose.toString(), null, null)
        )).isInstanceOf(InvalidScheduledWindowException.class)
                .hasMessageContaining("differ from the current value");

        verify(orderLifecycleService, never()).customerUpdateScheduled(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateScheduledOrder_storeClosedOnRequestedDay_throwsInvalidScheduledWindow() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = scheduledOrder(orderId, OffsetDateTime.now().plusHours(3));
        when(orderLifecycleService.loadScheduledOrderForCustomer(orderId.toString(), "user-1")).thenReturn(order);

        // Find a slot at least 60 min in future, aligned to 15-min, but on a SUNDAY
        // We stub working hours to exclude SUNDAY
        OffsetDateTime nextSunday = OffsetDateTime.now()
                .with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.SUNDAY))
                .withHour(10).withMinute(0).withSecond(0).withNano(0);

        StoreWorkingHoursResponse monToSatOnly = StoreWorkingHoursResponse.builder()
                .openTime(LocalTime.of(8, 0))
                .closeTime(LocalTime.of(19, 0))
                .workingDays(EnumSet.of(
                        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY))
                .build();

        when(storeServiceClient.getWorkingHours("store-1")).thenReturn(monToSatOnly);

        assertThatThrownBy(() -> service.updateScheduledOrder(
                "user-1", orderId.toString(),
                new UpdateScheduledOrderRequest(nextSunday.toString(), null, null)
        )).isInstanceOf(InvalidScheduledWindowException.class)
                .hasMessageContaining("not open on");

        verify(orderLifecycleService, never()).customerUpdateScheduled(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateScheduledOrder_outsideStoreHours_throwsInvalidScheduledWindow() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = scheduledOrder(orderId, OffsetDateTime.now().plusHours(3));
        when(orderLifecycleService.loadScheduledOrderForCustomer(orderId.toString(), "user-1")).thenReturn(order);

        // Store opens 08:00–18:00; a window starting at 18:00 ends at 18:30 → exceeds closeTime
        OffsetDateTime lateSlot = OffsetDateTime.now().plusDays(1)
                .withHour(18).withMinute(0).withSecond(0).withNano(0);

        StoreWorkingHoursResponse narrowHours = StoreWorkingHoursResponse.builder()
                .openTime(LocalTime.of(8, 0))
                .closeTime(LocalTime.of(18, 0))
                .workingDays(EnumSet.allOf(DayOfWeek.class))
                .build();

        when(storeServiceClient.getWorkingHours("store-1")).thenReturn(narrowHours);

        assertThatThrownBy(() -> service.updateScheduledOrder(
                "user-1", orderId.toString(),
                new UpdateScheduledOrderRequest(lateSlot.toString(), null, null)
        )).isInstanceOf(InvalidScheduledWindowException.class)
                .hasMessageContaining("falls outside store hours");

        verify(orderLifecycleService, never()).customerUpdateScheduled(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateScheduledOrder_storeServiceFallbackHours_validSlotPasses() {
        UUID orderId = UUID.randomUUID();
        // Use a fixed future date well within store hours (10:00 tomorrow) to avoid time-of-day flakiness
        OffsetDateTime currentScheduledFor = OffsetDateTime.now().plusDays(1)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime newSlot = currentScheduledFor.plusMinutes(15);
        OrderEntity order = scheduledOrder(orderId, currentScheduledFor);
        OrderEntity saved = scheduledOrder(orderId, newSlot);
        ScheduledOrderDetailResponse detailResponse = minimalDetail(orderId.toString(), newSlot);

        when(orderLifecycleService.loadScheduledOrderForCustomer(orderId.toString(), "user-1")).thenReturn(order);
        // Simulate store service fallback: returns 08:00-19:00, all days
        when(storeServiceClient.getWorkingHours("store-1")).thenReturn(allDayWorkingHours());
        when(orderLifecycleService.customerUpdateScheduled(
                eq(orderId.toString()), eq("user-1"),
                any(OffsetDateTime.class), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(saved);
        when(queryService.getScheduledOrderDetail("user-1", orderId.toString())).thenReturn(detailResponse);

        ScheduledOrderDetailResponse result = service.updateScheduledOrder(
                "user-1", orderId.toString(),
                new UpdateScheduledOrderRequest(newSlot.toString(), null, null)
        );

        assertThat(result).isSameAs(detailResponse);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private OrderEntity scheduledOrder(UUID id, OffsetDateTime scheduledFor) {
        OffsetDateTime now = OffsetDateTime.now();
        return OrderEntity.builder()
                .id(id)
                .customerId("customer-1")
                .storeId("store-1")
                .cartId("cart-1")
                .status(OrderStatus.SCHEDULED)
                .scheduleType(ScheduleType.SCHEDULED)
                .scheduledFor(scheduledFor)
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

    private StoreWorkingHoursResponse allDayWorkingHours() {
        return StoreWorkingHoursResponse.builder()
                .openTime(LocalTime.of(8, 0))
                .closeTime(LocalTime.of(19, 0))
                .workingDays(EnumSet.allOf(DayOfWeek.class))
                .build();
    }

    private ScheduledOrderDetailResponse minimalDetail(String orderId, OffsetDateTime scheduledFor) {
        return new ScheduledOrderDetailResponse(
                orderId, "#GR-001",
                scheduledFor.toString(),
                scheduledFor.plusMinutes(30).toString(),
                "Sebet Market", "store-1",
                null, List.of(), null, "UZS", true,
                OffsetDateTime.now().toString()
        );
    }
}
