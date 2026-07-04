package com.sebet.order_service.integration.checkout.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedEvent;
import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedItem;
import com.sebet.order_service.integration.checkout.event.CheckoutDeliveryAddress;
import com.sebet.order_service.integration.checkout.event.CheckoutStoreLocation;
import com.sebet.order_service.order.command.CreateOrderCommand;
import com.sebet.order_service.order.command.CreateOrderItemCommand;
import com.sebet.order_service.shared.enums.ProductUnit;
import com.sebet.order_service.shared.enums.ScheduleType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutConfirmedEventMapperTest {

    private final CheckoutConfirmedEventMapper mapper = new CheckoutConfirmedEventMapper(new ObjectMapper());

    @Test
    void mapsImmediateCheckoutEventToCreateOrderCommand() {
        CheckoutConfirmedEvent event = event("cart-1", ScheduleType.IMMEDIATE, null);

        CreateOrderCommand command = mapper.toCreateOrderCommand(event);

        assertThat(command.cartId()).isEqualTo("cart-1");
        assertThat(command.customerId()).isEqualTo("customer-1");
        assertThat(command.storeId()).isEqualTo("store-1");
        assertThat(command.scheduleType()).isEqualTo(ScheduleType.IMMEDIATE);
        assertThat(command.scheduledFor()).isNull();
        assertThat(command.subtotalAmount()).isEqualByComparingTo(new BigDecimal("33000.00"));
        assertThat(command.itemDiscountAmount()).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(command.orderDiscountAmount()).isEqualByComparingTo(new BigDecimal("3000.00"));
        assertThat(command.deliveryFeeAmount()).isEqualByComparingTo(new BigDecimal("8000.00"));
        assertThat(command.totalAmount()).isEqualByComparingTo(new BigDecimal("36000.00"));
        assertThat(command.currency()).isEqualTo("UZS");
        assertThat(command.deliveryAddressJson())
                .contains("\"street\":\"Amir Temur 25\"")
                .contains("\"city\":\"Tashkent\"")
                .contains("\"apartment\":\"42\"");
        assertThat(command.deliveryLat()).isEqualByComparingTo(new BigDecimal("41.311100"));
        assertThat(command.deliveryLng()).isEqualByComparingTo(new BigDecimal("69.279700"));
        assertThat(command.storeLat()).isEqualByComparingTo(new BigDecimal("41.320100"));
        assertThat(command.storeLng()).isEqualByComparingTo(new BigDecimal("69.240500"));
    }

    @Test
    void mapsScheduledCheckoutEventToCreateOrderCommand() {
        OffsetDateTime scheduledFor = OffsetDateTime.parse("2026-07-05T10:00:00Z");
        CheckoutConfirmedEvent event = event("cart-2", ScheduleType.SCHEDULED, scheduledFor);

        CreateOrderCommand command = mapper.toCreateOrderCommand(event);

        assertThat(command.scheduleType()).isEqualTo(ScheduleType.SCHEDULED);
        assertThat(command.scheduledFor()).isEqualTo(scheduledFor);
    }

    @Test
    void preservesItemOrderAndItemAmounts() {
        CheckoutConfirmedEvent event = event("cart-3", ScheduleType.IMMEDIATE, null);

        CreateOrderCommand command = mapper.toCreateOrderCommand(event);

        assertThat(command.items())
                .extracting(CreateOrderItemCommand::productId)
                .containsExactly("product-1", "product-2");
        assertThat(command.items().get(0).grossAmount()).isEqualByComparingTo(new BigDecimal("24000.00"));
        assertThat(command.items().get(0).discountAmount()).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(command.items().get(0).netAmount()).isEqualByComparingTo(new BigDecimal("22000.00"));
        assertThat(command.items().get(1).grossAmount()).isEqualByComparingTo(new BigDecimal("9000.00"));
        assertThat(command.items().get(1).discountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(command.items().get(1).netAmount()).isEqualByComparingTo(new BigDecimal("9000.00"));
    }

    @Test
    void mapsNullableStoreLocation() {
        CheckoutConfirmedEvent source = event("cart-4", ScheduleType.IMMEDIATE, null);
        CheckoutConfirmedEvent event = new CheckoutConfirmedEvent(
                source.cartId(),
                source.customerId(),
                source.storeId(),
                source.scheduleType(),
                source.scheduledFor(),
                source.subtotalAmount(),
                source.itemDiscountAmount(),
                source.orderDiscountAmount(),
                source.deliveryFeeAmount(),
                source.totalAmount(),
                source.currency(),
                source.deliveryAddress(),
                null,
                source.items()
        );

        CreateOrderCommand command = mapper.toCreateOrderCommand(event);

        assertThat(command.storeLat()).isNull();
        assertThat(command.storeLng()).isNull();
    }

    private CheckoutConfirmedEvent event(String cartId, ScheduleType scheduleType, OffsetDateTime scheduledFor) {
        return new CheckoutConfirmedEvent(
                cartId,
                "customer-1",
                "store-1",
                scheduleType,
                scheduledFor,
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
                List.of(
                        new CheckoutConfirmedItem(
                                "product-1",
                                "Apples",
                                new BigDecimal("2.000"),
                                ProductUnit.KG,
                                new BigDecimal("12000.00"),
                                new BigDecimal("24000.00"),
                                new BigDecimal("2000.00"),
                                new BigDecimal("22000.00"),
                                "https://cdn.sebet.test/products/apple.png"
                        ),
                        new CheckoutConfirmedItem(
                                "product-2",
                                "Milk",
                                new BigDecimal("1.000"),
                                ProductUnit.PCS,
                                new BigDecimal("9000.00"),
                                new BigDecimal("9000.00"),
                                BigDecimal.ZERO,
                                new BigDecimal("9000.00"),
                                null
                        )
                )
        );
    }
}
