package com.sebet.order_service.integration.checkout.consumer;

import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedEvent;
import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedItem;
import com.sebet.order_service.integration.checkout.event.CheckoutDeliveryAddress;
import com.sebet.order_service.integration.checkout.event.CheckoutStoreLocation;
import com.sebet.order_service.shared.enums.ProductUnit;
import com.sebet.order_service.shared.enums.ScheduleType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class CheckoutConfirmedEventConsumerTest {

    private final CheckoutConfirmedEventProcessor processor = mock(CheckoutConfirmedEventProcessor.class);
    private final CheckoutConfirmedEventConsumer consumer = new CheckoutConfirmedEventConsumer(processor);

    @Test
    void delegatesCheckoutEventToProcessor() {
        CheckoutConfirmedEvent event = checkoutEvent("cart-1");

        consumer.consume(event);

        verify(processor).process(event);
        verifyNoMoreInteractions(processor);
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
