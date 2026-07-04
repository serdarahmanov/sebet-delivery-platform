package com.sebet.order_service.integration.checkout.consumer;

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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CheckoutConfirmedEventConsumerTest {

    private final CheckoutConfirmedEventMapper mapper = mock(CheckoutConfirmedEventMapper.class);
    private final OrderCreationService orderCreationService = mock(OrderCreationService.class);
    private final CheckoutConfirmedEventConsumer consumer = new CheckoutConfirmedEventConsumer(
            mapper,
            orderCreationService
    );

    @Test
    void consumesCheckoutEventByMappingAndCreatingOrder() {
        CheckoutConfirmedEvent event = checkoutEvent("cart-1");
        CreateOrderCommand command = mock(CreateOrderCommand.class);
        OrderEntity order = OrderEntity.builder()
                .id(UUID.randomUUID())
                .cartId("cart-1")
                .status(OrderStatus.PENDING)
                .build();

        when(mapper.toCreateOrderCommand(event)).thenReturn(command);
        when(orderCreationService.createOrder(command)).thenReturn(new CreateOrderResult(order, true));

        consumer.consume(event);

        verify(mapper).toCreateOrderCommand(event);
        verify(orderCreationService).createOrder(command);
        verifyNoMoreInteractions(mapper, orderCreationService);
    }

    @Test
    void duplicateCheckoutEventReturnsExistingOrderWithoutError() {
        CheckoutConfirmedEvent event = checkoutEvent("cart-duplicate");
        CreateOrderCommand command = mock(CreateOrderCommand.class);
        OrderEntity order = OrderEntity.builder()
                .id(UUID.randomUUID())
                .cartId("cart-duplicate")
                .status(OrderStatus.PENDING)
                .build();

        when(mapper.toCreateOrderCommand(event)).thenReturn(command);
        when(orderCreationService.createOrder(command)).thenReturn(new CreateOrderResult(order, false));

        consumer.consume(event);

        verify(mapper).toCreateOrderCommand(event);
        verify(orderCreationService).createOrder(command);
        verifyNoMoreInteractions(mapper, orderCreationService);
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
