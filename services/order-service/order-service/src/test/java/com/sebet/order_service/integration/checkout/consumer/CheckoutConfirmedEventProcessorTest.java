package com.sebet.order_service.integration.checkout.consumer;

import com.sebet.order_service.cache.repository.OrderLockRedisRepository;
import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedEvent;
import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedItem;
import com.sebet.order_service.integration.checkout.event.CheckoutDeliveryAddress;
import com.sebet.order_service.integration.checkout.event.CheckoutStoreLocation;
import com.sebet.order_service.integration.checkout.mapper.CheckoutConfirmedEventMapper;
import com.sebet.order_service.order.command.CreateOrderCommand;
import com.sebet.order_service.order.command.CreateOrderResult;
import com.sebet.order_service.order.service.OrderCreationService;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ProductUnit;
import com.sebet.order_service.shared.enums.ScheduleType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CheckoutConfirmedEventProcessorTest {

    private static final String INSTANCE_ID = "order-service-test-instance";

    private final CheckoutConfirmedEventMapper mapper = mock(CheckoutConfirmedEventMapper.class);
    private final OrderCreationService orderCreationService = mock(OrderCreationService.class);
    private final OrderLockRedisRepository orderLockRedisRepository = mock(OrderLockRedisRepository.class);
    private final CheckoutConfirmedEventProcessor processor = new CheckoutConfirmedEventProcessor(
            mapper,
            orderCreationService,
            orderLockRedisRepository,
            INSTANCE_ID
    );

    @Test
    void createsOrderWhenLockIsAcquiredAndReleasesLock() {
        CheckoutConfirmedEvent event = checkoutEvent("cart-1");
        CreateOrderCommand command = mock(CreateOrderCommand.class);
        OrderEntity order = order("cart-1");

        when(orderLockRedisRepository.tryAcquire("cart-1", INSTANCE_ID)).thenReturn(true);
        when(mapper.toCreateOrderCommand(event)).thenReturn(command);
        when(orderCreationService.createOrder(command)).thenReturn(new CreateOrderResult(order, true));
        when(orderLockRedisRepository.release("cart-1", INSTANCE_ID)).thenReturn(true);

        processor.process(event);

        verify(orderLockRedisRepository).tryAcquire("cart-1", INSTANCE_ID);
        verify(mapper).toCreateOrderCommand(event);
        verify(orderCreationService).createOrder(command);
        verify(orderLockRedisRepository).release("cart-1", INSTANCE_ID);
        verifyNoMoreInteractions(mapper, orderCreationService, orderLockRedisRepository);
    }

    @Test
    void releasesLockForDuplicateCheckoutEvent() {
        CheckoutConfirmedEvent event = checkoutEvent("cart-duplicate");
        CreateOrderCommand command = mock(CreateOrderCommand.class);
        OrderEntity order = order("cart-duplicate");

        when(orderLockRedisRepository.tryAcquire("cart-duplicate", INSTANCE_ID)).thenReturn(true);
        when(mapper.toCreateOrderCommand(event)).thenReturn(command);
        when(orderCreationService.createOrder(command)).thenReturn(new CreateOrderResult(order, false));
        when(orderLockRedisRepository.release("cart-duplicate", INSTANCE_ID)).thenReturn(true);

        processor.process(event);

        verify(orderLockRedisRepository).release("cart-duplicate", INSTANCE_ID);
    }

    @Test
    void releasesLockAndPropagatesCreationFailure() {
        CheckoutConfirmedEvent event = checkoutEvent("cart-fails");
        CreateOrderCommand command = mock(CreateOrderCommand.class);
        IllegalStateException failure = new IllegalStateException("database unavailable");

        when(orderLockRedisRepository.tryAcquire("cart-fails", INSTANCE_ID)).thenReturn(true);
        when(mapper.toCreateOrderCommand(event)).thenReturn(command);
        when(orderCreationService.createOrder(command)).thenThrow(failure);
        when(orderLockRedisRepository.release("cart-fails", INSTANCE_ID)).thenReturn(true);

        assertThatThrownBy(() -> processor.process(event)).isSameAs(failure);

        verify(orderLockRedisRepository).release("cart-fails", INSTANCE_ID);
    }

    @Test
    void throwsRetryableExceptionWhenLockIsUnavailable() {
        CheckoutConfirmedEvent event = checkoutEvent("cart-locked");

        when(orderLockRedisRepository.tryAcquire("cart-locked", INSTANCE_ID)).thenReturn(false);

        assertThatThrownBy(() -> processor.process(event))
                .isInstanceOf(CheckoutOrderLockUnavailableException.class)
                .hasMessageContaining("cart-locked");

        verify(orderLockRedisRepository).tryAcquire("cart-locked", INSTANCE_ID);
        verify(orderLockRedisRepository, never()).release(any(), any());
        verifyNoMoreInteractions(mapper, orderCreationService);
    }

    @Test
    void doesNotHideSuccessfulProcessingWhenReleaseReturnsFalse() {
        CheckoutConfirmedEvent event = checkoutEvent("cart-release-false");
        CreateOrderCommand command = mock(CreateOrderCommand.class);
        OrderEntity order = order("cart-release-false");

        when(orderLockRedisRepository.tryAcquire("cart-release-false", INSTANCE_ID)).thenReturn(true);
        when(mapper.toCreateOrderCommand(event)).thenReturn(command);
        when(orderCreationService.createOrder(command)).thenReturn(new CreateOrderResult(order, true));
        when(orderLockRedisRepository.release("cart-release-false", INSTANCE_ID)).thenReturn(false);

        assertThatCode(() -> processor.process(event)).doesNotThrowAnyException();

        verify(orderLockRedisRepository).release("cart-release-false", INSTANCE_ID);
    }

    @Test
    void concurrentDuplicateCheckoutEventsOnlyAllowOneCreationPath() throws Exception {
        CheckoutConfirmedEvent event = checkoutEvent("cart-race");
        CreateOrderCommand command = mock(CreateOrderCommand.class);
        OrderEntity order = order("cart-race");
        AtomicBoolean lockHeld = new AtomicBoolean(false);
        CountDownLatch creationStarted = new CountDownLatch(1);
        CountDownLatch allowCreationToFinish = new CountDownLatch(1);

        when(orderLockRedisRepository.tryAcquire("cart-race", INSTANCE_ID))
                .thenAnswer(invocation -> lockHeld.compareAndSet(false, true));
        when(orderLockRedisRepository.release("cart-race", INSTANCE_ID))
                .thenAnswer(invocation -> {
                    lockHeld.set(false);
                    return true;
                });
        when(mapper.toCreateOrderCommand(event)).thenReturn(command);
        when(orderCreationService.createOrder(command)).thenAnswer(invocation -> {
            creationStarted.countDown();
            assertThat(allowCreationToFinish.await(5, TimeUnit.SECONDS)).isTrue();
            return new CreateOrderResult(order, true);
        });

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executorService.submit(() -> processor.process(event));
            assertThat(creationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executorService.submit(() -> {
                assertThatThrownBy(() -> processor.process(event))
                        .isInstanceOf(CheckoutOrderLockUnavailableException.class);
            });

            second.get(5, TimeUnit.SECONDS);
            allowCreationToFinish.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            allowCreationToFinish.countDown();
            executorService.shutdownNow();
        }

        verify(orderCreationService, times(1)).createOrder(command);
        verify(orderLockRedisRepository, times(2)).tryAcquire("cart-race", INSTANCE_ID);
        verify(orderLockRedisRepository, times(1)).release("cart-race", INSTANCE_ID);
    }

    private OrderEntity order(String cartId) {
        return OrderEntity.builder()
                .id(UUID.randomUUID())
                .cartId(cartId)
                .status(OrderStatus.PENDING)
                .build();
    }

    private CheckoutConfirmedEvent checkoutEvent(String cartId) {
        return new CheckoutConfirmedEvent(
                cartId,
                "customer-1",
                "store-1",
                ScheduleType.IMMEDIATE,
                null,
                new BigDecimal("33000.00"),
                new BigDecimal("2000.00"),
                new BigDecimal("3000.00"),
                new BigDecimal("8000.00"),
                new BigDecimal("36000.00"),
                "UZS",
                new CheckoutDeliveryAddress(
                        "Amir Temur 25",
                        "Tashkent",
                        new BigDecimal("41.311100"),
                        new BigDecimal("69.279700"),
                        "42",
                        "2",
                        "5",
                        "Call before arrival"
                ),
                new CheckoutStoreLocation(
                        new BigDecimal("41.320100"),
                        new BigDecimal("69.240500")
                ),
                List.of(new CheckoutConfirmedItem(
                        "product-1",
                        "Apples",
                        new BigDecimal("2.000"),
                        ProductUnit.KG,
                        new BigDecimal("12000.00"),
                        new BigDecimal("24000.00"),
                        new BigDecimal("2000.00"),
                        new BigDecimal("22000.00"),
                        "https://cdn.sebet.test/products/apple.png"
                ))
        );
    }
}
