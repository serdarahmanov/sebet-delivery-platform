package com.sebet.order_service.integration.checkout;

import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedPayload;
import com.sebet.order_service.integration.checkout.event.CheckoutItemPayload;
import com.sebet.order_service.integration.checkout.event.CheckoutScheduleType;
import com.sebet.order_service.integration.checkout.event.DeliverySnapshot;
import com.sebet.order_service.integration.checkout.event.IntegrationEvent;
import com.sebet.order_service.integration.checkout.event.MoneyBreakdown;
import com.sebet.order_service.integration.checkout.event.StoreLocationSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CheckoutEventTestFactory {

    private CheckoutEventTestFactory() {
    }

    public static IntegrationEvent<CheckoutConfirmedPayload> checkoutEvent(String cartId) {
        return checkoutEvent(UUID.randomUUID(), cartId, CheckoutScheduleType.ASAP, null);
    }

    public static IntegrationEvent<CheckoutConfirmedPayload> checkoutEvent(
            UUID eventId,
            String cartId,
            CheckoutScheduleType scheduleType,
            Instant scheduledFor
    ) {
        Instant occurredAt = Instant.parse("2026-07-08T12:00:00Z");
        return new IntegrationEvent<>(
                eventId,
                "CheckoutConfirmed",
                1,
                "Cart",
                cartId,
                occurredAt,
                "cart-service",
                payload(cartId, scheduleType, scheduledFor, occurredAt)
        );
    }

    public static CheckoutConfirmedPayload payload(
            String cartId,
            CheckoutScheduleType scheduleType,
            Instant scheduledFor,
            Instant confirmedAt
    ) {
        return new CheckoutConfirmedPayload(
                cartId + ":store-1",
                cartId,
                "store-1",
                "customer-1",
                "address-1",
                "quote-1",
                money(),
                deliveryAddress(),
                storeLocation(),
                items(),
                List.of("PROMO10"),
                scheduleType,
                scheduledFor,
                confirmedAt
        );
    }

    public static MoneyBreakdown money() {
        return new MoneyBreakdown(
                33000L,
                2000L,
                3000L,
                8000L,
                0L,
                0L,
                36000L,
                "UZS"
        );
    }

    public static DeliverySnapshot deliveryAddress() {
        return new DeliverySnapshot(
                "address-1",
                "Home",
                "Amir Temur 25",
                "Tashkent",
                new BigDecimal("41.311100"),
                new BigDecimal("69.279700"),
                "42",
                "2",
                "5",
                "Call before arrival",
                "+998901234567"
        );
    }

    public static StoreLocationSnapshot storeLocation() {
        return new StoreLocationSnapshot(
                "store-1",
                "Sebet Market Chilanzar",
                new BigDecimal("41.320100"),
                new BigDecimal("69.240500"),
                "Chilanzar 12"
        );
    }

    public static List<CheckoutItemPayload> items() {
        return List.of(
                new CheckoutItemPayload(
                        "cart-item-1",
                        "product-1",
                        "store-1",
                        "sku-1",
                        "Apples",
                        "KG",
                        new BigDecimal("2.000"),
                        12000L,
                        24000L,
                        2000L,
                        22000L,
                        "https://cdn.sebet.test/products/apple.png"
                ),
                new CheckoutItemPayload(
                        "cart-item-2",
                        "product-2",
                        "store-1",
                        "sku-2",
                        "Milk",
                        "PCS",
                        new BigDecimal("1.000"),
                        9000L,
                        9000L,
                        0L,
                        9000L,
                        null
                )
        );
    }
}
