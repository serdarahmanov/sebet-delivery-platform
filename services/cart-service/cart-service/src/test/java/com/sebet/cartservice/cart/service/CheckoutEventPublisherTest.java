package com.sebet.cartservice.cart.service;

import com.sebet.cartservice.cart.checkout.event.CheckoutConfirmedEvent;
import com.sebet.cartservice.cart.checkout.publisher.CheckoutEventPublisher;
import com.sebet.cartservice.cart.enums.ScheduleType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckoutEventPublisherTest {

    @Test
    void publishesEnvelopeJsonStringWithCartIdKey() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        CheckoutEventPublisher publisher = new CheckoutEventPublisher(kafkaTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(publisher, "checkoutEventsTopic", "checkout-events");
        CheckoutConfirmedEvent event = checkoutEvent();

        when(kafkaTemplate.send(eq("checkout-events"), eq("cart-1"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publish(event);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("checkout-events"), eq("cart-1"), payloadCaptor.capture());

        String payload = payloadCaptor.getValue();
        assertThat(payload)
                .contains("\"eventType\":\"CheckoutConfirmed\"")
                .contains("\"eventVersion\":1")
                .contains("\"aggregateId\":\"cart-1\"")
                .contains("\"aggregateType\":\"Cart\"")
                .contains("\"source\":\"cart-service\"")
                .contains("\"customerId\":\"customer-1\"")
                .contains("\"money\"")
                .contains("\"totalAmount\":36000")
                .contains("\"deliveryAddress\"")
                .contains("\"storeLocation\"")
                .contains("\"productName\":\"Apples\"")
                .contains("\"unitPriceAmount\":12000")
                .contains("\"grossAmount\":24000")
                .contains("\"discountAmount\":0")
                .contains("\"netAmount\":24000")
                .contains("\"scheduleType\":\"ASAP\"");
        assertThat(payload).doesNotContain("userId", "IMMEDIATE", "\"name\":\"Apples\"", "\"unitPrice\":");
    }

    private CheckoutConfirmedEvent checkoutEvent() {
        Instant now = Instant.parse("2026-07-08T12:00:00Z");
        return new CheckoutConfirmedEvent(
                "11111111-1111-1111-1111-111111111111",
                "CheckoutConfirmed",
                1,
                "cart-1",
                "Cart",
                now,
                "cart-service",
                new CheckoutConfirmedEvent.Data(
                        "basket-1",
                        "cart-1",
                        "store-1",
                        "customer-1",
                        "address-1",
                        "quote-1",
                        new CheckoutConfirmedEvent.Money(
                                33000L,
                                0L,
                                0L,
                                3000L,
                                0L,
                                0L,
                                36000L,
                                "UZS"
                        ),
                        new CheckoutConfirmedEvent.DeliveryAddress(
                                "address-1",
                                "Home",
                                "Amir Temur 25",
                                "Tashkent",
                                new BigDecimal("41.3111"),
                                new BigDecimal("69.2797"),
                                "12",
                                "2",
                                "4",
                                "call me"
                        ),
                        new CheckoutConfirmedEvent.StoreLocation(
                                "store-1",
                                "Sebet Market Chilanzar",
                                new BigDecimal("41.3201"),
                                new BigDecimal("69.2405"),
                                "Chilanzar 12"
                        ),
                        List.of(new CheckoutConfirmedEvent.Item(
                                "cart-item-1",
                                "product-1",
                                "store-1",
                                "sku-1",
                                "Apples",
                                "PCS",
                                new BigDecimal("2"),
                                12000L,
                                24000L,
                                0L,
                                24000L,
                                "https://cdn.sebet.test/products/apple.png"
                        )),
                        List.of("PROMO10"),
                        ScheduleType.ASAP,
                        null,
                        now
                )
        );
    }
}
