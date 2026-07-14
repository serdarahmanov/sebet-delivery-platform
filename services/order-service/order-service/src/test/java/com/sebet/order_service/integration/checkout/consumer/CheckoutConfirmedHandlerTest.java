package com.sebet.order_service.integration.checkout.consumer;

import com.sebet.order_service.cache.repository.OrderLockRedisRepository;
import com.sebet.order_service.integration.checkout.CheckoutEventTestFactory;
import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedPayload;
import com.sebet.order_service.integration.checkout.event.CheckoutScheduleType;
import com.sebet.order_service.integration.checkout.event.IntegrationEvent;
import com.sebet.order_service.integration.checkout.event.MoneyBreakdown;
import com.sebet.order_service.integration.checkout.mapper.CheckoutConfirmedEventMapper;
import com.sebet.order_service.integration.checkout.service.ProcessedEventWriter;
import com.sebet.order_service.integration.checkout.service.ProcessedEventWriter.ProcessedEventReservation;
import com.sebet.order_service.order.command.CreateOrderCommand;
import com.sebet.order_service.order.command.CreateOrderResult;
import com.sebet.order_service.order.service.OrderCreationService;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.shared.enums.OrderStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CheckoutConfirmedHandlerTest {

    private static final String INSTANCE_ID = "order-service-test-instance";

    private final CheckoutConfirmedEventMapper mapper = mock(CheckoutConfirmedEventMapper.class);
    private final OrderCreationService orderCreationService = mock(OrderCreationService.class);
    private final OrderLockRedisRepository orderLockRedisRepository = mock(OrderLockRedisRepository.class);
    private final ProcessedEventWriter processedEventWriter = mock(ProcessedEventWriter.class);
    private final CheckoutConfirmedHandler handler = new CheckoutConfirmedHandler(
            mapper,
            orderCreationService,
            orderLockRedisRepository,
            processedEventWriter,
            INSTANCE_ID
    );

    private static final String OWNER_TOKEN = "order-service-test-instance:reservation";

    @Test
    void createsOrderWhenLockIsAcquiredAndRecordsProcessedEvent() {
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent("cart-1");
        CreateOrderCommand command = mock(CreateOrderCommand.class);
        OrderEntity order = order("cart-1");

        when(processedEventWriter.reserve(any(), any())).thenReturn(new ProcessedEventReservation(true, OWNER_TOKEN));
        when(orderLockRedisRepository.tryAcquire("cart-1", INSTANCE_ID)).thenReturn(true);
        when(mapper.toCreateOrderCommand(event)).thenReturn(command);
        when(orderCreationService.createOrder(command)).thenReturn(new CreateOrderResult(order, true));
        when(orderLockRedisRepository.release("cart-1", INSTANCE_ID)).thenReturn(true);

        handler.handle(event);

        verify(orderLockRedisRepository).tryAcquire("cart-1", INSTANCE_ID);
        verify(mapper).toCreateOrderCommand(event);
        verify(orderCreationService).createOrder(command);
        verify(processedEventWriter).markCompleted(event.eventId(), OWNER_TOKEN);
        verify(orderLockRedisRepository).release("cart-1", INSTANCE_ID);
    }

    @Test
    void skipsAlreadyProcessedEventBeforeLocking() {
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent("cart-duplicate-event");
        when(processedEventWriter.reserve(any(), any())).thenReturn(new ProcessedEventReservation(false, null));

        handler.handle(event);

        verify(processedEventWriter).reserve(any(), any());
        verifyNoMoreInteractions(processedEventWriter);
        verifyNoMoreInteractions(orderLockRedisRepository, mapper, orderCreationService);
    }

    @Test
    void propagatesInProgressReservationBeforeLocking() {
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent("cart-in-progress");
        ProcessedEventInProgressException failure = new ProcessedEventInProgressException(event.eventId());
        doThrow(failure).when(processedEventWriter).reserve(any(), any());

        assertThatThrownBy(() -> handler.handle(event)).isSameAs(failure);

        verify(processedEventWriter).reserve(any(), any());
        verifyNoMoreInteractions(orderLockRedisRepository, mapper, orderCreationService);
    }

    @Test
    void savesProcessedEventForDuplicateCartIdAfterIdempotentOrderServiceReturn() {
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent("cart-duplicate");
        CreateOrderCommand command = mock(CreateOrderCommand.class);
        OrderEntity order = order("cart-duplicate");

        when(processedEventWriter.reserve(any(), any())).thenReturn(new ProcessedEventReservation(true, OWNER_TOKEN));
        when(orderLockRedisRepository.tryAcquire("cart-duplicate", INSTANCE_ID)).thenReturn(true);
        when(mapper.toCreateOrderCommand(event)).thenReturn(command);
        when(orderCreationService.createOrder(command)).thenReturn(new CreateOrderResult(order, false));
        when(orderLockRedisRepository.release("cart-duplicate", INSTANCE_ID)).thenReturn(true);

        handler.handle(event);

        verify(processedEventWriter).markCompleted(event.eventId(), OWNER_TOKEN);
        verify(orderLockRedisRepository).release("cart-duplicate", INSTANCE_ID);
    }

    @Test
    void releasesLockAndPropagatesCreationFailure() {
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent("cart-fails");
        CreateOrderCommand command = mock(CreateOrderCommand.class);
        IllegalStateException failure = new IllegalStateException("database unavailable");

        when(processedEventWriter.reserve(any(), any())).thenReturn(new ProcessedEventReservation(true, OWNER_TOKEN));
        when(orderLockRedisRepository.tryAcquire("cart-fails", INSTANCE_ID)).thenReturn(true);
        when(mapper.toCreateOrderCommand(event)).thenReturn(command);
        when(orderCreationService.createOrder(command)).thenThrow(failure);
        when(orderLockRedisRepository.release("cart-fails", INSTANCE_ID)).thenReturn(true);

        assertThatThrownBy(() -> handler.handle(event)).isSameAs(failure);

        verify(orderLockRedisRepository).release("cart-fails", INSTANCE_ID);
        verify(processedEventWriter).releaseInProgress(event.eventId(), OWNER_TOKEN);
        verify(processedEventWriter, never()).markCompleted(any(), any());
    }

    @Test
    void throwsRetryableExceptionWhenLockIsUnavailable() {
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent("cart-locked");

        when(processedEventWriter.reserve(any(), any())).thenReturn(new ProcessedEventReservation(true, OWNER_TOKEN));
        when(orderLockRedisRepository.tryAcquire("cart-locked", INSTANCE_ID)).thenReturn(false);

        assertThatThrownBy(() -> handler.handle(event))
                .isInstanceOf(CheckoutOrderLockUnavailableException.class)
                .hasMessageContaining("cart-locked");

        verify(orderLockRedisRepository).tryAcquire("cart-locked", INSTANCE_ID);
        verify(processedEventWriter).releaseInProgress(event.eventId(), OWNER_TOKEN);
        verify(orderLockRedisRepository, never()).release(any(), any());
        verifyNoMoreInteractions(mapper, orderCreationService);
    }

    @Test
    void doesNotHideSuccessfulProcessingWhenReleaseReturnsFalse() {
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent("cart-release-false");
        CreateOrderCommand command = mock(CreateOrderCommand.class);
        OrderEntity order = order("cart-release-false");

        when(processedEventWriter.reserve(any(), any())).thenReturn(new ProcessedEventReservation(true, OWNER_TOKEN));
        when(orderLockRedisRepository.tryAcquire("cart-release-false", INSTANCE_ID)).thenReturn(true);
        when(mapper.toCreateOrderCommand(event)).thenReturn(command);
        when(orderCreationService.createOrder(command)).thenReturn(new CreateOrderResult(order, true));
        when(orderLockRedisRepository.release("cart-release-false", INSTANCE_ID)).thenReturn(false);

        assertThatCode(() -> handler.handle(event)).doesNotThrowAnyException();

        verify(orderLockRedisRepository).release("cart-release-false", INSTANCE_ID);
        verify(processedEventWriter).markCompleted(event.eventId(), OWNER_TOKEN);
    }

    @Test
    void propagatesProcessedEventCompletionFailureAfterRedisInitialization() {
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent("cart-processed-write-fails");
        CreateOrderCommand command = mock(CreateOrderCommand.class);
        OrderEntity order = order("cart-processed-write-fails");
        IllegalStateException failure = new IllegalStateException("processed_events insert failed");

        when(processedEventWriter.reserve(any(), any())).thenReturn(new ProcessedEventReservation(true, OWNER_TOKEN));
        when(orderLockRedisRepository.tryAcquire("cart-processed-write-fails", INSTANCE_ID)).thenReturn(true);
        when(mapper.toCreateOrderCommand(event)).thenReturn(command);
        when(orderCreationService.createOrder(command)).thenReturn(new CreateOrderResult(order, true));
        doThrow(failure).when(processedEventWriter).markCompleted(event.eventId(), OWNER_TOKEN);
        when(orderLockRedisRepository.release("cart-processed-write-fails", INSTANCE_ID)).thenReturn(true);

        assertThatThrownBy(() -> handler.handle(event)).isSameAs(failure);

        verify(orderCreationService).createOrder(command);
        verify(processedEventWriter).markCompleted(event.eventId(), OWNER_TOKEN);
        verify(processedEventWriter).releaseInProgress(event.eventId(), OWNER_TOKEN);
        verify(orderLockRedisRepository).release("cart-processed-write-fails", INSTANCE_ID);
    }

    @Test
    void rejectsMissingMoney() {
        CheckoutConfirmedPayload source = CheckoutEventTestFactory.payload(
                "cart-missing-money",
                CheckoutScheduleType.ASAP,
                null,
                Instant.parse("2026-07-08T12:00:00Z")
        );
        IntegrationEvent<CheckoutConfirmedPayload> event = eventWithData(new CheckoutConfirmedPayload(
                source.basketId(),
                source.cartId(),
                source.storeId(),
                source.customerId(),
                source.addressId(),
                source.feeQuoteId(),
                null,
                source.deliveryAddress(),
                source.storeLocation(),
                source.items(),
                source.selectedPromoCodes(),
                source.scheduleType(),
                source.scheduledFor(),
                source.confirmedAt()
        ), "cart-missing-money");

        assertThatThrownBy(() -> handler.handle(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data.money must not be null");
    }

    @Test
    void rejectsMissingDeliveryAddress() {
        IntegrationEvent<CheckoutConfirmedPayload> event = eventWithData(new CheckoutConfirmedPayload(
                "cart-missing-address:store-1",
                "cart-missing-address",
                "store-1",
                "customer-1",
                "address-1",
                "quote-1",
                CheckoutEventTestFactory.money(),
                null,
                CheckoutEventTestFactory.storeLocation(),
                CheckoutEventTestFactory.items(),
                List.of(),
                CheckoutScheduleType.ASAP,
                null,
                Instant.parse("2026-07-08T12:00:00Z")
        ), "cart-missing-address");

        assertThatThrownBy(() -> handler.handle(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data.deliveryAddress must not be null");
    }

    @Test
    void rejectsMissingStoreLocation() {
        IntegrationEvent<CheckoutConfirmedPayload> event = eventWithData(new CheckoutConfirmedPayload(
                "cart-missing-store-location:store-1",
                "cart-missing-store-location",
                "store-1",
                "customer-1",
                "address-1",
                "quote-1",
                CheckoutEventTestFactory.money(),
                CheckoutEventTestFactory.deliveryAddress(),
                null,
                CheckoutEventTestFactory.items(),
                List.of(),
                CheckoutScheduleType.ASAP,
                null,
                Instant.parse("2026-07-08T12:00:00Z")
        ), "cart-missing-store-location");

        assertThatThrownBy(() -> handler.handle(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data.storeLocation must not be null");
    }

    @Test
    void rejectsEmptyItems() {
        IntegrationEvent<CheckoutConfirmedPayload> event = eventWithData(new CheckoutConfirmedPayload(
                "cart-empty-items:store-1",
                "cart-empty-items",
                "store-1",
                "customer-1",
                "address-1",
                "quote-1",
                CheckoutEventTestFactory.money(),
                CheckoutEventTestFactory.deliveryAddress(),
                CheckoutEventTestFactory.storeLocation(),
                List.of(),
                List.of(),
                CheckoutScheduleType.ASAP,
                null,
                Instant.parse("2026-07-08T12:00:00Z")
        ), "cart-empty-items");

        assertThatThrownBy(() -> handler.handle(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data.items must not be empty");
    }

    @Test
    void rejectsScheduledWithoutScheduledFor() {
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent(
                UUID.randomUUID(),
                "cart-scheduled-missing-time",
                CheckoutScheduleType.SCHEDULED,
                null
        );

        assertThatThrownBy(() -> handler.handle(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheduledFor must not be null");
    }

    @Test
    void rejectsAsapWithScheduledFor() {
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent(
                UUID.randomUUID(),
                "cart-asap-with-time",
                CheckoutScheduleType.ASAP,
                Instant.parse("2026-07-09T10:00:00Z")
        );

        assertThatThrownBy(() -> handler.handle(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheduledFor must be null");
    }

    @Test
    void rejectsAggregateIdMismatch() {
        IntegrationEvent<CheckoutConfirmedPayload> source = CheckoutEventTestFactory.checkoutEvent("cart-data");
        IntegrationEvent<CheckoutConfirmedPayload> event = new IntegrationEvent<>(
                source.eventId(),
                source.eventType(),
                source.eventVersion(),
                source.aggregateType(),
                "different-cart",
                source.occurredAt(),
                source.source(),
                source.data()
        );

        assertThatThrownBy(() -> handler.handle(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregateId must equal data.cartId");
    }

    private IntegrationEvent<CheckoutConfirmedPayload> eventWithData(
            CheckoutConfirmedPayload data,
            String aggregateId
    ) {
        return new IntegrationEvent<>(
                UUID.randomUUID(),
                "CheckoutConfirmed",
                1,
                "Cart",
                aggregateId,
                Instant.parse("2026-07-08T12:00:00Z"),
                "cart-service",
                data
        );
    }

    private OrderEntity order(String cartId) {
        return OrderEntity.builder()
                .id(UUID.randomUUID())
                .cartId(cartId)
                .status(OrderStatus.PENDING)
                .build();
    }
}
